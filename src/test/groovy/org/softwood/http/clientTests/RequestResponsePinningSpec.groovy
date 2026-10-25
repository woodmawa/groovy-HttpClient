package org.softwood.http.clientTests

import groovy.json.JsonSlurper
import org.softwood.http.GroovyHttpClient
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.AutoCleanup
import spock.lang.Specification

/**
 * Unit-level pins for request/response behaviour that until 2.1 was only covered by the real-server matrix
 * (that gap is how the query double-encoding bug hid). These assert what the server RECEIVED, so the 2.1 codec
 * registry, which reroutes all of these paths, has to keep them true.
 */
class RequestResponsePinningSpec extends Specification {

    MockHttpServer server = new MockHttpServer()
    @AutoCleanup GroovyHttpClient client

    def setup() {
        server.init()
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
    }

    def cleanup() {
        server.shutdown()
    }

    // ---------------------------------------------------------------- request.query

    @Feature('request.query')
    def "query values with reserved and non-ASCII characters arrive decoded exactly once"() {
        given:
        def check = server.addRequestCheck('GET', '/q', 200)

        when:
        client.getSync('/q') { query(a: '1', b: 'two words & more', c: 'é/?', d: 'x=y', e: 'a+b', f: '100%') }

        then:
        def got = check.lastRequest
        got.query == [a: ['1'], b: ['two words & more'], c: ['é/?'], d: ['x=y'], e: ['a+b'], f: ['100%']]
        !got.rawQuery.contains('%25%')      // a double-encoded escape would show up as %25XX
        !got.rawQuery.contains(' ')
    }

    @Feature('request.query')
    def "multi-valued and null query parameters"() {
        given:
        def check = server.addRequestCheck('GET', '/q', 200)

        when:
        client.getSync('/q') { query(tag: ['red', 'blue', null], skip: null, n: 3) }

        then:
        check.lastRequest.query == [tag: ['red', 'blue'], n: ['3']]
    }

    // ---------------------------------------------------------------- request.json-body

    @Feature('request.json-body')
    def "json(map) sends a UTF-8 JSON body with its content type"() {
        given:
        def check = server.addRequestCheck('POST', '/j', 200)

        when:
        client.postSync('/j', null) { json([n: 1, s: 'é', list: [1, 2], nested: [ok: true]]) }

        then:
        def got = check.lastRequest
        got.header('Content-Type') == 'application/json; charset=UTF-8'
        new JsonSlurper().parseText(got.bodyText) == [n: 1, s: 'é', list: [1, 2], nested: [ok: true]]
    }

    @Feature('request.json-body')
    def "json(String) is sent verbatim, not re-encoded"() {
        given:
        def check = server.addRequestCheck('POST', '/j', 200)

        when:
        client.postSync('/j', null) { json('{"raw" : true}') }

        then:
        check.lastRequest.bodyText == '{"raw" : true}'
    }

    // ---------------------------------------------------------------- request.form-body

    @Feature('request.form-body')
    def "form(map) sends a url-encoded body that decodes back to the map"() {
        given:
        def check = server.addRequestCheck('POST', '/f', 200)

        when:
        client.postSync('/f', null) { form(a: '1', b: 'x y&z', c: 'é=1') }

        then:
        def got = check.lastRequest
        got.header('Content-Type') == 'application/x-www-form-urlencoded; charset=UTF-8'
        got.form == [a: ['1'], b: ['x y&z'], c: ['é=1']]
    }

    // ---------------------------------------------------------------- request.interceptors

    @Feature('request.interceptors')
    def "interceptors wrap the call in the order added and see the final response"() {
        given:
        server.addRequestCheck('GET', '/i', 204)
        def seen = Collections.synchronizedList([])
        client.addInterceptor { ctx, next ->
            seen << "outer:${ctx.method}".toString()
            next.call(ctx).thenApply { r -> seen << "outer:${r.statusCode}".toString(); r }
        }
        client.addInterceptor { ctx, next ->
            seen << 'inner'
            next.call(ctx).thenApply { r -> seen << "inner:${r.statusCode}".toString(); r }
        }

        when:
        client.getSync('/i')

        then:
        seen == ['outer:GET', 'inner', 'inner:204', 'outer:204']
    }

    @Feature('request.interceptors')
    def "an interceptor that throws fails the request with that exception"() {
        given:
        def check = server.addRequestCheck('GET', '/i', 200)
        client.addInterceptor { ctx, next -> throw new IllegalStateException('blocked by interceptor') }

        when:
        client.getSync('/i')

        then:
        def e = thrown(IllegalStateException)
        e.message == 'blocked by interceptor'
        check.received.isEmpty()
    }

    // ---------------------------------------------------------------- response.json

    @Feature('response.json')
    def "a JSON response parses for application/json and structured +json types"() {
        given:
        server.addRequestCheck('GET', '/r', 200)
                .withResponseBody('{"greeting":"hello","n":[1,2]}')
                .withResponseHeaders(['Content-Type': contentType])

        when:
        def r = client.getSync('/r')

        then:
        r.json == [greeting: 'hello', n: [1, 2]]
        r.jsonOrNull == r.json
        r.requireJson().greeting == 'hello'

        where:
        contentType << ['application/json', 'application/json; charset=UTF-8', 'application/problem+json', 'application/vnd.tmf+json']
    }

    @Feature('response.json')
    def "a non-JSON response gives null from jsonOrNull and ContentParseException from json"() {
        given:
        server.addRequestCheck('GET', '/r', 200)
                .withResponseBody('{"looks":"like json"}')
                .withResponseHeaders(['Content-Type': 'text/plain'])

        when:
        def r = client.getSync('/r')

        then:
        r.jsonOrNull == null

        when:
        r.json

        then:
        thrown(GroovyHttpClient.ContentParseException)
    }
}
