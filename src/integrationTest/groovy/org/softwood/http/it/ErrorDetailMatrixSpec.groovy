package org.softwood.http.it

import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.features.Feature

/** 2.1 error detail against the real server, on every protocol. */
class ErrorDetailMatrixSpec extends IntegrationSpec {

    static final String PROBLEM = '{"type":"https://example.com/probs/out-of-credit","title":"Out of credit","status":403,"detail":"SECRET-DETAIL","balance":30}'

    @Feature('response.error-detail')
    def "error detail is on the exception, not in its message, over #protocol"() {
        given:
        def c = client(protocol) { errorBodyPreviewChars(0) }

        when:
        c.getSync('/status/404') { query(type: 'application/json', body: '{"secret":"SECRET-BODY"}', token: 'SECRET-QUERY') }

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 404
        e.reasonPhrase == 'Not Found'
        e.method == 'GET'
        e.message.startsWith('HTTP 404 Not Found (GET ')
        e.message.contains(', 24 bytes, application/json)')
        !e.message.contains('SECRET')
        e.json == [secret: 'SECRET-BODY']
        e.uri.rawQuery.contains('SECRET-QUERY')

        where:
        protocol << Protocol.available()
    }

    @Feature('response.problem-details')
    def "application/problem+json becomes e.problem over #protocol"() {
        when:
        client(protocol).getSync('/status/403') { query(type: 'application/problem+json', body: PROBLEM) }

        then:
        def e = thrown(HttpResponseException)
        e.problem.title == 'Out of credit'
        e.problem.status == 403
        e.problem.extensions.balance == 30
        e.contentType.essence == 'application/problem+json'

        where:
        protocol << Protocol.available()
    }

    @Feature('response.size-limit-exception')
    def "a body over maxResponseBytes raises ResponseTooLargeException over #protocol"() {
        given:
        def c = client(protocol) { maxResponseBytes(1000) }

        when:
        c.getSync('/bytes?n=200000')

        then:
        def e = thrown(ResponseTooLargeException)
        e.statusCode == 413
        e.limit == 1000L
        e.bytesRead > 1000L
        e.method == 'GET'
        e.message.startsWith('Response exceeded maxResponseBytes (1000) (GET ')

        where:
        protocol << Protocol.available()
    }
}
