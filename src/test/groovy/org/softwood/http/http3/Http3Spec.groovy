package org.softwood.http.http3

import org.softwood.http.GroovyHttpClient
import org.softwood.http.Http3Discovery
import org.softwood.http.HttpVersion
import org.softwood.http.SecurityConfig
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Unroll

import java.nio.file.Path
import java.time.Duration

/**
 * HTTP/3 support (JEP 517, JDK 26+).
 *
 * Runs differently per JDK: on JDK 27 (task test) it exercises real HTTP/3 against an in-process QUIC
 * server; on JDK 25 (task testJava25) it proves the fallback to HTTP/2 and the refusal of HTTP/3-only.
 */
@Timeout(60)
class Http3Spec extends Specification {

    static boolean jdkHasHttp3() { Runtime.version().feature() >= 26 }

    @Shared @TempDir
    Path pemDir

    /**
     * ONE QUIC server for the whole spec. Starting a second quiche server in the same JVM after stopping
     * the first crashes the process natively on Windows (0xC0000005, Jetty 12.1.14 / quiche 0.30.0), so the
     * server is shared and stopped once, in cleanupSpec.
     */
    @Shared Http3TestServer server

    def setupSpec() {
        if (jdkHasHttp3()) server = new Http3TestServer(pemDir)
    }

    def cleanupSpec() {
        server?.close()
    }

    // ---- configuration (every JDK) -------------------------------------------------------------

    def "isHttp3Available reflects the running JDK"() {
        expect:
        GroovyHttpClient.http3Available == jdkHasHttp3()
    }

    @Unroll
    def "httpVersion accepts #input"() {
        expect:
        SecurityConfig.defaults('https://api.example.com').withOverrides(httpVersion: input).httpVersion == expected

        where:
        input              | expected
        'HTTP/3'           | HttpVersion.HTTP_3
        'http/1.1'         | HttpVersion.HTTP_1_1
        'HTTP_2'           | HttpVersion.HTTP_2
        HttpVersion.HTTP_3 | HttpVersion.HTTP_3
    }

    def "the default is HTTP/2"() {
        expect:
        SecurityConfig.defaults('https://api.example.com').httpVersion == HttpVersion.HTTP_2
        SecurityConfig.production('https://api.example.com').httpVersion == HttpVersion.HTTP_2
    }

    def "a discovery mode implies HTTP/3, including on top of a profile"() {
        when:
        def cfg = SecurityConfig.production('https://api.example.com').withOverrides(http3Discovery: 'ALT_SVC')

        then:
        cfg.httpVersion == HttpVersion.HTTP_3
        cfg.http3Discovery == Http3Discovery.ALT_SVC
    }

    @Unroll
    def "invalid HTTP/3 configuration is refused: #why"() {
        when:
        SecurityConfig.defaults('https://api.example.com').withOverrides(overrides)

        then:
        thrown(IllegalArgumentException)

        where:
        why                               | overrides
        'HTTP/3 without TLS 1.3'          | [httpVersion: 'HTTP_3', allowedTlsProtocols: ['TLSv1.2']]
        'discovery with HTTP/2'           | [httpVersion: 'HTTP_2', http3Discovery: 'ANY']
        'unknown version name'            | [httpVersion: 'HTTP/4']
    }

    // ---- JDK 25: fallback ------------------------------------------------------------------------

    @Requires({ !Http3Spec.jdkHasHttp3() })
    def "without JDK HTTP/3 a client asking for HTTP/3 falls back to HTTP/2"() {
        given:
        def client = GroovyHttpClient.builder('https://api.example.com').http3(Http3Discovery.ANY).build()

        expect:
        client.httpVersion == HttpVersion.HTTP_2

        cleanup:
        client?.close()
    }

    @Requires({ !Http3Spec.jdkHasHttp3() })
    def "without JDK HTTP/3 an HTTP/3-only client refuses to build"() {
        when:
        GroovyHttpClient.builder('https://api.example.com').http3(Http3Discovery.HTTP_3_URI_ONLY).build()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('JDK 26')
    }

    // ---- JDK 26+: real HTTP/3 --------------------------------------------------------------------

    @Requires({ Http3Spec.jdkHasHttp3() })
    def "the client prefers HTTP/3 when asked"() {
        given:
        def client = GroovyHttpClient.builder('https://api.example.com').http3().build()

        expect:
        client.httpVersion == HttpVersion.HTTP_3

        cleanup:
        client?.close()
    }

    @Requires({ Http3Spec.jdkHasHttp3() })
    def "end to end: a GET over HTTP/3 (QUIC) to a local server"() {
        given:
        def client = GroovyHttpClient.builder("https://localhost:${server.port}")
                .trustStore(server.trustStore)
                .http3(Http3Discovery.HTTP_3_URI_ONLY)
                .build()

        when:
        def resp = client.getSync('/hello')

        then:
        resp.statusCode == 200
        resp.httpVersion == HttpVersion.HTTP_3
        resp.body == 'hello over HTTP/3.0'

        cleanup:
        client?.close()
    }

    @Requires({ Http3Spec.jdkHasHttp3() })
    def "end to end: one request opts into HTTP/3 on an HTTP/2 client"() {
        given:
        def client = GroovyHttpClient.builder("https://localhost:${server.port}")
                .trustStore(server.trustStore)
                .build()

        when:
        def resp = client.getSync('/hello') { http3(Http3Discovery.HTTP_3_URI_ONLY) }

        then:
        client.httpVersion == HttpVersion.HTTP_2
        resp.httpVersion == HttpVersion.HTTP_3

        cleanup:
        client?.close()
    }

    @Requires({ Http3Spec.jdkHasHttp3() })
    def "end to end: HTTP/3 still verifies the certificate"() {
        given:
        def client = GroovyHttpClient.builder("https://localhost:${server.port}")
                .http3(Http3Discovery.HTTP_3_URI_ONLY)     // no trustStore: the self-signed cert is not trusted
                .requestTimeout(Duration.ofSeconds(5))
                .build()

        when:
        client.getSync('/hello')

        then:
        thrown(IOException)

        cleanup:
        client?.close()
    }

    @Requires({ Http3Spec.jdkHasHttp3() })
    def "HTTP/3-only does not silently fall back when nothing answers over QUIC"() {
        given:
        int port = new DatagramSocket(0).withCloseable { it.localPort }   // a UDP port nobody is listening on
        def client = GroovyHttpClient.builder("https://localhost:${port}")
                .http3(Http3Discovery.HTTP_3_URI_ONLY)
                .requestTimeout(Duration.ofSeconds(3))
                .build()

        when:
        client.getSync('/hello')

        then:
        thrown(IOException)

        cleanup:
        client?.close()
    }
}
