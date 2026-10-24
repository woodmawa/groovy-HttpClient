package org.softwood.http.security

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import org.softwood.http.GroovyHttpClient
import org.softwood.http.SecurityConfig
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * Real TLS against an in-process HTTPS server using src/test/resources/keystore.jks
 * (self-signed, CN=localhost, SAN dns:localhost ip:127.0.0.1).
 *
 * SEC-1 behaviourally: the default client refuses an untrusted certificate.
 * SEC-13: trustStore / keyManagers -- documented in 1.x but silently ignored -- now take effect,
 * and unknown setting names are refused.
 */
@Timeout(30)
class TlsTrustSpec extends Specification {

    static final char[] PASSWORD = 'password'.toCharArray()

    HttpsServer server
    String base
    boolean requireClientCert = false
    KeyStore serverKeys
    KeyStore trustedCert

    def setup() {
        serverKeys = KeyStore.getInstance('JKS')
        getClass().getResourceAsStream('/keystore.jks').withCloseable { serverKeys.load(it, PASSWORD) }

        // a trust store holding only the test certificate -- what a client (or server, for mTLS) should trust
        trustedCert = KeyStore.getInstance('PKCS12')
        trustedCert.load(null, null)
        trustedCert.setCertificateEntry('test-server', serverKeys.getCertificate('wiremock'))

        def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        kmf.init(serverKeys, PASSWORD)
        def tmf = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
        tmf.init(trustedCert)
        SSLContext serverCtx = SSLContext.getInstance('TLS')
        serverCtx.init(kmf.keyManagers, tmf.trustManagers, null)

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName('127.0.0.1'), 0), 0)
        def spec = this
        server.httpsConfigurator = new HttpsConfigurator(serverCtx) {
            @Override
            void configure(HttpsParameters params) {
                def p = serverCtx.defaultSSLParameters
                p.needClientAuth = spec.requireClientCert
                params.setSSLParameters(p)
            }
        }
        server.createContext('/hello') { ex ->
            byte[] b = 'hello over TLS'.getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.write(b)
            ex.close()
        }
        server.start()
        base = "https://localhost:${server.address.port}"
    }

    def cleanup() {
        server?.stop(0)
    }

    private javax.net.ssl.KeyManager[] clientKeyManagers() {
        def kmf = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        kmf.init(serverKeys, PASSWORD)   // the test cert doubles as the client certificate
        kmf.keyManagers
    }

    def "SEC-1 the default client refuses a certificate it does not trust"() {
        given:
        def client = new GroovyHttpClient(base)

        when:
        client.getSync('/hello')

        then:
        thrown(SSLHandshakeException)

        cleanup:
        client?.close()
    }

    @Unroll
    def "SEC-13 trusting the test certificate via #route lets the default profile connect"() {
        given:
        def client = new GroovyHttpClient(cfg.call(base, trustedCert) as SecurityConfig)

        expect:
        client.getSync('/hello').body == 'hello over TLS'

        cleanup:
        client?.close()

        where:
        route                  | cfg
        'withOverrides'        | { String b, KeyStore ts -> SecurityConfig.defaults(b).withOverrides(trustStore: ts) }
        'production profile'   | { String b, KeyStore ts -> SecurityConfig.production(b).withOverrides(trustStore: ts) }
        'Builder'              | { String b, KeyStore ts -> GroovyHttpClient.builder(b).trustStore(ts).build().config }
    }

    def "trustAll connects without any trust material (and is the only profile that does)"() {
        given:
        def client = new GroovyHttpClient(SecurityConfig.trustAll(base))

        expect:
        client.getSync('/hello').statusCode == 200

        cleanup:
        client?.close()
    }

    def "testing() verifies certificates unless given an SSLContext"() {
        given:
        def tmf = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
        tmf.init(trustedCert)
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, tmf.trustManagers, null)
        def strict = new GroovyHttpClient(SecurityConfig.testing(base))
        def withCtx = new GroovyHttpClient(SecurityConfig.testing(base, ctx))

        when:
        def refused = (Throwable) null
        try { strict.getSync('/hello') } catch (Throwable t) { refused = t }

        then:
        refused instanceof SSLHandshakeException
        withCtx.getSync('/hello').statusCode == 200

        cleanup:
        strict?.close()
        withCtx?.close()
    }

    @Unroll
    def "SEC-13 mutual TLS: a server requiring a client certificate #outcome when keyManagers are #state"() {
        given:
        requireClientCert = true
        def builder = GroovyHttpClient.builder(base).trustStore(trustedCert)
        if (withClientCert) builder.keyManagers(clientKeyManagers())
        def client = builder.build()

        when:
        def result = (Object) null
        try { result = client.getSync('/hello') } catch (Throwable t) { result = t }

        then:
        withClientCert ? (result instanceof GroovyHttpClient.HttpClientResponse && result.statusCode == 200)
                       : (result instanceof IOException)

        cleanup:
        client?.close()

        where:
        withClientCert << [true, false]
        state = withClientCert ? 'supplied' : 'absent'
        outcome = withClientCert ? 'accepts the request' : 'rejects the handshake'
    }

    @Unroll
    def "SEC-13 an unknown setting name is refused: #key"() {
        when:
        SecurityConfig.production('https://api.example.com').withOverrides([(key): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(key)

        where:
        key                       | value
        'trustStorePath'          | '/etc/ssl/ts.p12'
        'allowRedirect'           | false
        'enforceHostVerification' | false
    }

    def "SEC-13 conflicting trust settings are refused"() {
        when:
        SecurityConfig.trustAll(base).withOverrides(trustStore: trustedCert)

        then:
        thrown(IllegalArgumentException)
    }
}
