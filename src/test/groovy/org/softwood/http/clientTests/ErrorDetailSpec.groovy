package org.softwood.http.clientTests

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.ProblemDetail
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

/** 2.1 error detail: what HttpResponseException says (and must never say) and what it carries. */
class ErrorDetailSpec extends Specification {

    static final String PROBLEM = '{"type":"https://example.com/probs/out-of-credit","title":"Out of credit","status":403,' +
            '"detail":"Balance 30, cost 50","instance":"/account/12345/msgs/abc","balance":30,"accounts":["/a/1"]}'

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0] + overrides))
    }

    // ================================================================ response.error-detail

    @Feature('response.error-detail')
    def "the message is status, reason, method, loggable URI, size and media type -- no body"() {
        given:
        server.addRequestCheck('GET', '/orders/42', 404)
                .withResponseBody('{"title":"no such order"}')
                .withResponseHeaders(['Content-Type': 'application/problem+json; charset=UTF-8'])
        def c = client()

        when:
        c.getSync('/orders/42?token=SECRET-Q')

        then:
        def e = thrown(HttpResponseException)
        e.message == "HTTP 404 Not Found (GET ${base()}/orders/42, 25 bytes, application/problem+json)".toString()
        e.toString() == "${HttpResponseException.name}: ${e.message}".toString()

        cleanup:
        c?.close()
    }

    @Feature('response.error-detail')
    def "the exception carries status, reason, method, full URI, headers, media type, body and bytes"() {
        given:
        server.addRequestCheck('POST', '/orders', 422)
                .withResponseBody('{"field":"qty","n":3}')
                .withResponseHeaders(['Content-Type': 'application/json', 'X-Request-Id': 'r-1'])
        def c = client()

        when:
        c.postSync('/orders?dry=true', '{"qty":-1}')

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 422
        e.reasonPhrase == 'Unprocessable Content'
        e.method == 'POST'
        e.uri.toString() == "${base()}/orders?dry=true".toString()
        e.header('x-request-id') == 'r-1'
        e.responseHeaders['X-REQUEST-ID'] == ['r-1']
        e.contentType.essence == 'application/json'
        e.responseBody == '{"field":"qty","n":3}'
        new String(e.responseBytes, 'UTF-8') == e.responseBody
        e.json == [field: 'qty', n: 3]
        e.parsed == [field: 'qty', n: 3]
        e.problem == null

        when: 'the bytes are a copy'
        e.responseBytes[0] = (byte) 'X'

        then:
        e.responseBody.startsWith('{')
        new String(e.responseBytes, 'UTF-8').startsWith('{')

        cleanup:
        c?.close()
    }

    static class FieldError {
        String field
        int n
    }

    @Feature('response.error-detail')
    def "bodyAs maps the error body to a type through the codec registry"() {
        given:
        server.addRequestCheck('GET', '/e', 400)
                .withResponseBody('{"field":"qty","n":3}')
                .withResponseHeaders(['Content-Type': 'application/json'])
        def c = client()

        when:
        c.getSync('/e')

        then:
        def e = thrown(HttpResponseException)
        e.bodyAs(FieldError).field == 'qty'
        e.bodyAs(FieldError).n == 3

        cleanup:
        c?.close()
    }

    @Feature('response.error-detail')
    def "a secret in the error body never reaches the message, toString or the log at the default setting"() {
        given:
        def logger = (Logger) LoggerFactory.getLogger(GroovyHttpClient)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        server.addRequestCheck('GET', '/err', 500).withResponseBody('{"debug":"password=SECRET-BODY-123"}')
        def c = GroovyHttpClient.builder(base()).enableLogging(true).build()

        when:
        c.getSync('/err?api_key=SECRET-QUERY-456')

        then:
        def e = thrown(HttpResponseException)
        e.responseBody.contains('SECRET-BODY-123')
        !e.message.contains('SECRET')
        !e.toString().contains('SECRET')
        appender.list.every { ILoggingEvent ev -> !ev.formattedMessage.contains('SECRET') && !(ev.throwableProxy?.message?.contains('SECRET')) }

        cleanup:
        logger.detachAppender(appender)
        c?.close()
    }

    @Feature('response.error-detail')
    def "errorBodyPreviewChars opts in to a flattened, truncated preview; never the query string"() {
        given:
        server.addRequestCheck('GET', '/err', 500).withResponseBody('line one\nline two ' + ('x' * 200))
        def c = client(errorBodyPreviewChars: 20)

        when:
        c.getSync('/err?token=SECRET-Q')

        then:
        def e = thrown(HttpResponseException)
        e.message.endsWith(': line one line two xx...')
        !e.message.contains('SECRET')

        cleanup:
        c?.close()
    }

    @Feature('response.error-detail')
    def "testing() previews 120 characters; the default profile and builder preview none"() {
        expect:
        SecurityConfig.testing('http://localhost').errorBodyPreviewChars == 120
        SecurityConfig.defaults('http://localhost').errorBodyPreviewChars == 0
        GroovyHttpClient.builder('http://localhost').build().config.errorBodyPreviewChars == 0
        GroovyHttpClient.builder('http://localhost').errorBodyPreviewChars(40).build().config.errorBodyPreviewChars == 40
    }

    @Feature('response.error-detail')
    def "errorBodyPreviewChars outside 0..4096 is refused"() {
        when:
        SecurityConfig.defaults('http://localhost').withOverrides(errorBodyPreviewChars: chars)

        then:
        thrown(IllegalArgumentException)

        where:
        chars << [-1, 4097]
    }

    @Feature('response.error-detail')
    def "a code without a standard phrase, and the deprecated 2.0 constructor"() {
        expect:
        new HttpResponseException(599, 'secret body').message == 'HTTP 599 (11 bytes)'
        new HttpResponseException(599, 'secret body').responseBody == 'secret body'
        new HttpResponseException(404, null).message == 'HTTP 404 Not Found (0 bytes)'
    }

    @Feature('response.error-detail')
    def "the exception survives serialisation; parsing needs the live registry"() {
        given:
        server.addRequestCheck('GET', '/e', 409)
                .withResponseBody('{"a":1}')
                .withResponseHeaders(['Content-Type': 'application/json'])
        def c = client()
        HttpResponseException original = null
        try { c.getSync('/e') } catch (HttpResponseException e) { original = e }

        when:
        def bytes = new ByteArrayOutputStream()
        new ObjectOutputStream(bytes).withCloseable { it.writeObject(original) }
        def copy = (HttpResponseException) new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject()

        then:
        copy.message == original.message
        copy.statusCode == 409
        copy.responseBody == '{"a":1}'
        copy.contentType.essence == 'application/json'
        copy.header('content-type') == 'application/json'

        when:
        copy.json

        then:
        thrown(IllegalStateException)

        cleanup:
        c?.close()
    }

    // ================================================================ response.problem-details

    @Feature('response.problem-details')
    def "application/problem+json is parsed as RFC 9457 problem details, extensions kept"() {
        given:
        server.addRequestCheck('GET', '/pay', 403)
                .withResponseBody(PROBLEM)
                .withResponseHeaders(['Content-Type': 'application/problem+json'])
        def c = client()

        when:
        c.getSync('/pay')

        then:
        def e = thrown(HttpResponseException)
        ProblemDetail p = e.problem
        p.type == 'https://example.com/probs/out-of-credit'
        p.title == 'Out of credit'
        p.status == 403
        p.detail == 'Balance 30, cost 50'
        p.instance == '/account/12345/msgs/abc'
        p.extensions == [balance: 30, accounts: ['/a/1']]
        !p.toString().contains('Balance')

        cleanup:
        c?.close()
    }

    @Feature('response.problem-details')
    def "problem is null for other types and for a malformed problem body; members of the wrong type are absent"() {
        expect:
        ProblemDetail.from([title: 7, status: 'x']).with { type == 'about:blank' && title == null && status == null }

        when:
        server.addRequestCheck('GET', '/a', 500).withResponseBody(PROBLEM).withResponseHeaders(['Content-Type': 'application/json'])
        server.addRequestCheck('GET', '/b', 500).withResponseBody('{not json').withResponseHeaders(['Content-Type': 'application/problem+json'])
        def c = client()
        def a = null, b = null
        try { c.getSync('/a') } catch (HttpResponseException e) { a = e }
        try { c.getSync('/b') } catch (HttpResponseException e) { b = e }

        then:
        a.problem == null
        b.problem == null

        cleanup:
        c?.close()
    }

    // ================================================================ response.size-limit-exception

    @Feature('response.size-limit-exception')
    def "a body over maxResponseBytes raises ResponseTooLargeException with the limit and the request"() {
        given:
        server.addRequestCheck('GET', '/big', 200).withResponseBody('y' * 50_000)
        def c = new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides(maxResponseBytes: 1000L))

        when:
        c.getSync('/big?token=SECRET-Q')

        then:
        def e = thrown(ResponseTooLargeException)
        e instanceof HttpResponseException
        e.statusCode == 413
        e.limit == 1000L
        e.bytesRead > 1000L
        e.method == 'GET'
        e.message == "Response exceeded maxResponseBytes (1000) (GET ${base()}/big)".toString()

        cleanup:
        c?.close()
    }

    @Feature('response.size-limit-exception')
    def "2.0 code that catches HttpResponseException and checks 413 still works"() {
        given:
        server.addRequestCheck('GET', '/big', 200).withResponseBody('y' * 5000)
        def c = new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides(maxResponseBytes: 100L))
        Integer seen = null

        when:
        try { c.getSync('/big') } catch (HttpResponseException e) { seen = e.statusCode }

        then:
        seen == 413

        cleanup:
        c?.close()
    }
}
