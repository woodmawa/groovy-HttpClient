package org.softwood.http.clientTests

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.Http3Discovery
import org.softwood.http.HttpVersion
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.http.proxy.ProxySupport
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * 2.1 proxy support. The mock server plays the proxy: for a plain-http target the client sends it the request with an
 * absolute URI, so a request arriving there proves the proxy was used (the target host does not even resolve).
 */
class ProxySpec extends Specification {

    static final String TARGET = 'http://api.target.invalid:8081'

    MockHttpServer proxy = new MockHttpServer()

    def setup() { proxy.init() }

    def cleanup() { proxy.shutdown() }

    GroovyHttpClient viaProxy(Closure extra = {}) {
        def b = GroovyHttpClient.builder(TARGET).proxy('localhost', proxy.port)
        b.with(extra)
        b.build()
    }

    @Feature('proxy.http')
    def "requests to a plain-http target go to the proxy with an absolute URI"() {
        given:
        def check = proxy.addRequestCheck('GET', '/orders', 200).withResponseBody('via proxy')

        when:
        def r = viaProxy().getSync('/orders?id=1')

        then:
        r.body == 'via proxy'
        check.lastRequest.uri.toString() == "${TARGET}/orders?id=1"
    }

    @Feature('proxy.http')
    def "allowedHosts still applies to the target, not the proxy"() {
        given:
        def check = proxy.addRequestCheck('GET', '/x', 200)

        when:
        GroovyHttpClient.builder(TARGET).allowAbsoluteUrls(true).proxy('localhost', proxy.port).build()
                .getSync('http://elsewhere.invalid/x')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Target host not allowed')
        check.received.isEmpty()
    }

    @Feature('proxy.http')
    def "nonProxyHosts patterns bypass the proxy"() {
        given:
        def sel = new ProxySupport.FixedProxySelector('proxy.corp', 3128, ['*.internal', 'localhost'])

        expect:
        sel.select(URI.create('http://svc.internal/x'))[0] == Proxy.NO_PROXY
        sel.select(URI.create('http://LOCALHOST:8080/x'))[0] == Proxy.NO_PROXY
        sel.select(URI.create('https://api.example.com/x'))[0].type() == Proxy.Type.HTTP
        sel.select(URI.create('http://internal.example.com/x'))[0].type() == Proxy.Type.HTTP
    }

    @Feature('proxy.http')
    def "noProxy ignores the JVM proxy settings"() {
        expect:
        ProxySupport.selectorFor(true, null, null, null).select(URI.create('http://x.example/'))[0] == Proxy.NO_PROXY
    }

    static final String PROXY_BASIC = 'Basic ' + Base64.encoder.encodeToString('proxy-user:proxy-pass'.getBytes(StandardCharsets.UTF_8))

    @Feature('proxy.auth')
    def "proxy credentials go preemptively to the proxy; a 407 surfaces as a response"() {
        given:
        def check = proxy.addRequestCheck('GET', '/orders', 407)
                .withResponseHeaders(['Proxy-Authenticate': 'Basic realm="corp"'])

        when:
        viaProxy { proxyAuth('proxy-user', 'proxy-pass') }.getSync('/orders')

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 407
        check.received.size() == 1
        check.received[0].header('Proxy-Authorization') == PROXY_BASIC
    }

    @Feature('proxy.auth')
    def "with proxyAuth set, a target's 401 is still a 401 response (no JDK Authenticator)"() {
        given:
        def check = proxy.addRequestCheck('GET', '/orders', 401)
                .withResponseHeaders(['WWW-Authenticate': 'Basic realm="api"'])

        when:
        viaProxy { proxyAuth('proxy-user', 'proxy-pass') }.getSync('/orders')

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 401
        check.received.size() == 1
        check.received[0].header('Authorization') == null
    }

    @Feature('proxy.auth')
    def "a target that bypasses the proxy never receives the proxy credentials"() {
        given:
        def direct = new MockHttpServer()
        direct.init()
        def check = direct.addRequestCheck('GET', '/d', 200)
        def c = GroovyHttpClient.builder("http://localhost:${direct.port}").proxy('proxy.unused.invalid', 3128)
                .proxyAuth('proxy-user', 'proxy-pass').nonProxyHosts('localhost').build()

        when:
        c.getSync('/d')

        then:
        check.received.size() == 1
        check.lastRequest.header('Proxy-Authorization') == null

        cleanup:
        direct.shutdown()
    }

    @Feature('proxy.socks')
    def "a SOCKS proxy is refused at build()"() {
        when:
        GroovyHttpClient.builder(TARGET).proxy('socks.corp', 1080, 'socks').build()

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('JDK-8214516')
    }

    @Feature('proxy.socks')
    def "a SOCKS proxy from the JVM default selector is warned about once"() {
        given:
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        ((Logger) LoggerFactory.getLogger(ProxySupport)).addAppender(appender)
        def socks = new ProxySelector() {
            List<Proxy> select(URI uri) { [new Proxy(Proxy.Type.SOCKS, new InetSocketAddress('socks.corp', 1080))] }
            void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
        }
        def sel = new ProxySupport.SocksWarningSelector(socks)

        when:
        sel.select(URI.create('https://a.example/'))
        sel.select(URI.create('https://b.example/'))

        then:
        appender.list.count { it.formattedMessage.contains('SOCKS') && it.formattedMessage.contains('going direct') } == 1

        cleanup:
        ((Logger) LoggerFactory.getLogger(ProxySupport)).detachAppender(appender)
    }

    @Feature('proxy.http')
    def "HTTP/3-only cannot use a proxy; HTTP/3 ANY falls back to HTTP/2"() {
        when:
        GroovyHttpClient.builder('https://example.com').http3(Http3Discovery.HTTP_3_URI_ONLY).proxy('proxy.corp', 3128).build()

        then:
        thrown(IllegalArgumentException)

        when:
        def c = GroovyHttpClient.builder('https://example.com').http3(Http3Discovery.ANY).proxy('proxy.corp', 3128).build()

        then:
        c.httpVersion == HttpVersion.HTTP_2
    }

    @Feature('proxy.http')
    def "proxy settings are validated"() {
        when:
        new GroovyHttpClient(SecurityConfig.defaults('https://example.com').withOverrides(settings))

        then:
        thrown(IllegalArgumentException)

        where:
        settings << [
                [proxyHost: 'p', proxyPort: 0],
                [proxyHost: 'p'],
                [proxyUser: 'u', proxyPassword: 'x'],
                [nonProxyHosts: ['*.internal']],
                [proxyHost: 'p', proxyPort: 3128, noProxy: true],
                [proxyHost: 'p', proxyPort: 3128, proxyUser: 'u'],
                [proxyHost: 'p', proxyPort: 3128, proxyType: 'ftp'],
        ]
    }

    @Feature('proxy.auth')
    def "a refused HTTPS tunnel is recognised for the clear message"() {
        expect:
        ProxySupport.isTunnel407(new IOException('Unable to tunnel through proxy. Proxy returns "HTTP/1.1 407 Proxy Authentication Required"'))
        !ProxySupport.isTunnel407(new IOException('Connection reset'))
        ProxySupport.tunnelRefusedMessage(true).contains('jdk.http.auth.tunneling.disabledSchemes')
        ProxySupport.tunnelRefusedMessage(false).contains('proxyAuth')
    }
}
