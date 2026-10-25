package org.softwood.http.it

import org.softwood.http.AuthenticationException
import org.softwood.http.features.Feature

/** 2.1 proxy support against the real server through a Jetty CONNECT proxy (HTTP/1.1 and HTTP/2; QUIC cannot be proxied). */
class ProxyMatrixSpec extends IntegrationSpec {

    static List<Protocol> tcpProtocols() { Protocol.available().findAll { it != Protocol.HTTP_3 } }

    @Feature('proxy.http')
    def "HTTPS goes through the proxy's CONNECT tunnel over #protocol"() {
        given:
        def tp = new TestProxy()

        when:
        def r = client(protocol) { proxy('localhost', tp.port) }.getSync('/json')

        then:
        r.json.greeting == 'hello'
        tp.connects.size() == 1
        tp.connects[0].endsWith(":${new URI(server.baseUrl(protocol)).port}")

        cleanup:
        tp.close()

        where:
        protocol << tcpProtocols()
    }

    @Feature('proxy.auth')
    def "proxy credentials open an authenticated tunnel over #protocol"() {
        given:
        def tp = new TestProxy('proxy-user', 'proxy-pass')

        when:
        def r = client(protocol) { proxy('localhost', tp.port); proxyAuth('proxy-user', 'proxy-pass') }.getSync('/echo')

        then: 'the CONNECT carried the credentials (no challenge round trip) and the target never saw them'
        r.json.method == 'GET'
        tp.connects.size() == 1
        tp.refused.isEmpty()
        !r.json.headers.containsKey('proxy-authorization')

        cleanup:
        tp.close()

        where:
        protocol << tcpProtocols()
    }

    @Feature('proxy.auth')
    def "wrong proxy credentials fail with an explanation over #protocol"() {
        given:
        def tp = new TestProxy('proxy-user', 'proxy-pass')

        when:
        client(protocol) { proxy('localhost', tp.port); proxyAuth('proxy-user', 'wrong') }.getSync('/json')

        then:
        def e = thrown(AuthenticationException)
        e.statusCode == 407
        e.message.contains('407')
        tp.connects.isEmpty()
        tp.refused.size() == 1

        cleanup:
        tp.close()

        where:
        protocol << tcpProtocols()
    }
}
