package org.softwood.http.it

import groovy.json.JsonSlurper
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.features.Feature

import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException

/**
 * The 2.0 feature set proven end to end against a real Jetty server, once per protocol
 * (HTTP/1.1 and HTTP/2 over TLS + ALPN; HTTP/3 over QUIC on JDK 26+).
 */
class CoreFeatureMatrixSpec extends IntegrationSpec {

    static Map echoOf(GroovyHttpClient.HttpClientResponse r) { (Map) new JsonSlurper().parseText(r.body) }

    @Feature('protocol.negotiated')
    def "a client pinned to #protocol talks #protocol end to end"() {
        when:
        def r = client(protocol).getSync('/echo')

        then: 'the client saw it'
        r.httpVersion.name() == protocol.name()

        and: 'so did the server'
        echoOf(r).protocol.startsWith(protocol.serverPrefix)

        where:
        protocol << Protocol.available()
    }

    @Feature('request.verbs')
    def "#method over #protocol reaches the server as #method"() {
        given:
        def c = client(protocol)

        when:
        def r = switch (method) {
            case 'GET'     -> c.get('/echo').get()
            case 'DELETE'  -> c.delete('/echo').get()
            case 'HEAD'    -> c.head('/echo').get()
            case 'OPTIONS' -> c.options('/echo').get()
            case 'POST'    -> c.post('/echo', 'payload').get()
            case 'PUT'     -> c.put('/echo', 'payload').get()
            case 'PATCH'   -> c.patch('/echo', 'payload').get()
        }

        then:
        r.statusCode == 200
        r.getHeader('X-Echo-Method') == method
        method == 'HEAD' || echoOf(r).method == method
        !(method in ['POST', 'PUT', 'PATCH']) || echoOf(r).body == 'payload'

        where:
        [protocol, method] << [Protocol.available(), ['GET', 'DELETE', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH']].combinations()
    }

    @Feature('request.sync')
    def "sync calls over #protocol throw the real failure; async wraps it"() {
        given:
        def c = client(protocol)

        when:
        c.getSync('/status/500')

        then:
        def sync = thrown(HttpResponseException)
        sync.statusCode == 500

        when:
        c.get('/status/500').get()

        then:
        def async = thrown(ExecutionException)
        async.cause instanceof HttpResponseException

        where:
        protocol << Protocol.available()
    }

    @Feature('request.headers')
    def "default and per-request headers reach the server over #protocol"() {
        given:
        def c = client(protocol).withHeader('X-Default', 'd')

        when:
        def echo = echoOf(c.getSync('/echo') { header('X-Per-Request', 'r') })

        then:
        echo.headers['x-default'] == ['d']
        echo.headers['x-per-request'] == ['r']

        where:
        protocol << Protocol.available()
    }

    @Feature('request.query')
    def "query parameters are encoded and decoded intact over #protocol"() {
        when:
        def echo = echoOf(client(protocol).getSync('/echo') { query(a: '1', b: 'two words & more', c: 'é/?') })

        then:
        echo.query == [a: ['1'], b: ['two words & more'], c: ['é/?']]

        where:
        protocol << Protocol.available()
    }

    @Feature('request.json-body')
    def "json() sends a JSON body with its content type over #protocol"() {
        when:
        def echo = echoOf(client(protocol).postSync('/echo', null) { json([n: 1, s: 'x', list: [1, 2]]) })

        then:
        echo.headers['content-type'][0].startsWith('application/json')
        new JsonSlurper().parseText(echo.body) == [n: 1, s: 'x', list: [1, 2]]

        where:
        protocol << Protocol.available()
    }

    @Feature('request.form-body')
    def "form() sends a url-encoded body over #protocol"() {
        when:
        def echo = echoOf(client(protocol).postSync('/echo', null) { form(a: '1', b: 'x y&z') })
        def fields = echo.body.split('&').collectEntries { kv ->
            def (k, v) = kv.split('=', 2)
            [(URLDecoder.decode(k, 'UTF-8')): URLDecoder.decode(v, 'UTF-8')]
        }

        then:
        echo.headers['content-type'][0].startsWith('application/x-www-form-urlencoded')
        fields == [a: '1', b: 'x y&z']

        where:
        protocol << Protocol.available()
    }

    @Feature('request.multipart')
    def "a multipart upload arrives as multipart/form-data over #protocol"() {
        when:
        def echo = echoOf(client(protocol).postMultipartSync('/echo') { b ->
            b.part {
                name 'file1'
                filename 'readme.txt'
                content 'Hello World'
            }
            b.part {
                name 'meta'
                contentType 'application/json'
                content '{"a":1}'
            }
        })

        then:
        echo.headers['content-type'][0].startsWith('multipart/form-data; boundary=')
        echo.body.contains('name="file1"')
        echo.body.contains('filename="readme.txt"')
        echo.body.contains('Hello World')
        echo.body.contains('{"a":1}')

        where:
        protocol << Protocol.available()
    }

    @Feature('request.cookies')
    def "store cookies and per-request cookies are sent over #protocol"() {
        given:
        def c = client(protocol)
        c.addCookie('session', 'abc')

        when:
        def fromStore = echoOf(c.getSync('/echo')).headers['cookie']?.join('; ')
        def perRequest = echoOf(client(protocol).getSync('/echo') { cookie('extra', 'x') }).headers['cookie']?.join('; ')

        then:
        fromStore?.contains('session=abc')
        perRequest?.contains('extra=x')

        where:
        protocol << Protocol.available()
    }

    @Feature('request.timeout')
    def "a per-request timeout cuts a slow response short over #protocol"() {
        given:
        def c = client(protocol)
        long started = System.nanoTime()

        when:
        c.getSync('/slow?ms=3000') { timeout(Duration.ofMillis(300)) }

        then:
        def e = thrown(Exception)
        rootCause(e) instanceof HttpTimeoutException
        (System.nanoTime() - started) < Duration.ofMillis(2500).toNanos()

        where:
        protocol << Protocol.available()
    }

    @Feature('request.interceptors')
    def "an interceptor sees the request and the response over #protocol"() {
        given:
        def seen = Collections.synchronizedList([])
        def c = client(protocol)
        c.addInterceptor { ctx, next ->
            seen << ctx.method
            next.call(ctx).thenApply { r -> seen << r.statusCode; r }
        }

        when:
        c.getSync('/echo')

        then:
        seen == ['GET', 200]

        where:
        protocol << Protocol.available()
    }

    @Feature('response.headers')
    def "response headers are case-insensitive and keep every value over #protocol"() {
        when:
        def r = client(protocol).getSync('/echo')

        then:
        r.getHeader('x-echo-method') == 'GET'
        r.getHeader('X-ECHO-METHOD') == 'GET'
        r.hasHeader('X-Multi')
        r.getHeaders('X-Multi') as Set == ['a', 'b'] as Set

        where:
        protocol << Protocol.available()
    }

    @Feature('response.json')
    def "a JSON response parses over #protocol"() {
        when:
        def r = client(protocol).getSync('/json')

        then:
        r.json.greeting == 'hello'
        r.jsonOrNull != null
        r.requireJson().protocol.startsWith(protocol.serverPrefix)

        where:
        protocol << Protocol.available()
    }

    @Feature('response.xml')
    def "an XML response parses over #protocol"() {
        when:
        def xml = client(protocol).getSync('/xml').xml

        then:
        xml.text() == 'hello'
        xml.@protocol.toString().startsWith(protocol.serverPrefix)

        where:
        protocol << Protocol.available()
    }

    @Feature('response.raise-for-status')
    def "a #status over #protocol raises HttpResponseException carrying the body"() {
        when:
        client(protocol).getSync("/status/${status}")

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == status
        e.responseBody == "body-for-${status}".toString()
        e.message.startsWith("HTTP ${status} ${e.reasonPhrase} (GET ")   // 2.1 format; body only via errorBodyPreviewChars

        where:
        [protocol, status] << [Protocol.available(), [404, 503]].combinations()
    }

    @Feature('response.max-bytes')
    def "maxResponseBytes admits a small body and aborts a large one over #protocol"() {
        given:
        def c = client(protocol) { maxResponseBytes(10_000) }

        expect:
        c.getSync('/bytes?n=5000').bytes.length == 5000

        when:
        c.getSync('/bytes?n=200000')

        then:
        thrown(Exception)

        where:
        protocol << Protocol.available()
    }

    @Feature('response.download-bytes')
    def "downloadBytes returns every byte over #protocol"() {
        expect:
        client(protocol).downloadBytesSync('/bytes?n=12345').length == 12345

        where:
        protocol << Protocol.available()
    }

    @Feature('redirect.follow')
    def "redirects are followed (#hops hops, #code) over #protocol"() {
        when:
        def r = method == 'POST'
                ? client(protocol).postSync("/redirect/${hops}?code=${code}", 'payload')
                : client(protocol).getSync("/redirect/${hops}?code=${code}")

        then:
        echoOf(r).path == '/echo'
        echoOf(r).method == expectedMethod

        where:
        [protocol, row] << [Protocol.available(), [
                [hops: 3, code: 302, method: 'GET',  expectedMethod: 'GET'],
                [hops: 1, code: 303, method: 'POST', expectedMethod: 'GET'],
                [hops: 1, code: 307, method: 'POST', expectedMethod: 'POST'],
        ]].combinations()
        hops = row.hops
        code = row.code
        method = row.method
        expectedMethod = row.expectedMethod
    }

    @Feature('redirect.follow')
    def "a redirect chain longer than maxRedirects is refused over #protocol"() {
        when:
        client(protocol) { maxRedirects(2) }.getSync('/redirect/5')

        then:
        def e = thrown(Exception)
        rootCause(e).message.contains('Too many redirects')

        where:
        protocol << Protocol.available()
    }
}
