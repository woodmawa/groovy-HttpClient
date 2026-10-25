package org.softwood.http.clientTests

import groovy.transform.CompileStatic
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.ResponseHandlers
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class ResponseHandlersSpec extends Specification {

    static class Order {
        String id
        int qty
    }

    MockHttpServer server = new MockHttpServer()
    @AutoCleanup GroovyHttpClient client

    def setup() {
        server.init()
        server.addRequestCheck('GET', '/ok', 200).withResponseBody('{"id":"o1","qty":2}').withResponseHeaders(['Content-Type': 'application/json'])
        [400, 401, 403, 404, 409, 422, 500, 503].each { code ->
            server.addRequestCheck('GET', "/s${code}".toString(), code).withResponseBody("body-${code}".toString())
        }
        server.addRequestCheck('GET', '/problem', 409)
                .withResponseBody('{"title":"Stale version","status":409}')
                .withResponseHeaders(['Content-Type': 'application/problem+json'])
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
    }

    def cleanup() { server.shutdown() }

    static Closure allHandlers() {
        return {
            success { r -> "success:${r.statusCode}".toString() }
            when(404) { r -> 'exact-404' }
            when(409, 422) { r -> "pair:${r.statusCode}".toString() }
            when([401, 403]) { r -> "list:${r.statusCode}".toString() }
            when(500..599) { r -> "range:${r.statusCode}".toString() }
            failure { r -> "failure:${r.statusCode}".toString() }
        }
    }

    @Feature('response.handlers')
    def "status #path goes to #expected"() {
        when:
        def v = client.getSync(Object, path) { response allHandlers() }

        then:
        v == expected

        where:
        path    || expected
        '/ok'   || 'success:200'
        '/s404' || 'exact-404'
        '/s409' || 'pair:409'
        '/s422' || 'pair:422'
        '/s401' || 'list:401'
        '/s403' || 'list:403'
        '/s500' || 'range:500'
        '/s503' || 'range:503'
        '/s400' || 'failure:400'
    }

    @Feature('response.handlers')
    def "precedence: exact beats list beats range beats failure"() {
        given:
        def h = ResponseHandlers.from {
            failure { 'failure' }
            when(400..499) { 'range' }
            when([404, 410]) { 'list' }
            when(404) { 'exact' }
        }

        expect:
        h.select(404).call() == 'exact'
        h.select(410).call() == 'list'
        h.select(418).call() == 'range'
        h.select(500).call() == 'failure'
        h.select(200) == null
    }

    @Feature('response.handlers')
    def "without a matching handler: below 400 returns the response, from 400 raises as before"() {
        when:
        def r = client.getSync('/ok') { response { when(404) { null } } }

        then:
        r instanceof GroovyHttpClient.HttpClientResponse
        r.statusCode == 200

        when:
        client.getSync('/s500') { response { when(404) { null } } }

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 500
    }

    @Feature('response.handlers')
    def "typed verbs: the handler's value, or the body mapped when the handler passes the response through"() {
        expect:
        client.getSync(Order, '/ok') { response { success { r -> r as Order } } }.qty == 2
        client.getSync(Order, '/ok') { response { when(404) { null } } }.id == 'o1'
        client.getSync(Order, '/s404') { response { when(404) { null } } } == null

        when:
        client.getSync(Order, '/s404') { response { when(404) { 'not an order' } } }

        then:
        def e = thrown(ClassCastException)
        e.message.contains(Order.name)
        e.message.contains('java.lang.String')
    }

    @Feature('response.handlers')
    def "untyped sync verbs keep their 2.0 type and point at the typed verb for handler values"() {
        expect:
        client.getSync('/s404') { response { when(404) { null } } } == null

        when:
        client.getSync('/s404') { response { when(404) { 'value' } } }

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('getSync(String, path)')
        e.message.contains('getSync(Object, path)')
    }

    @Feature('response.handlers')
    def "async verbs complete with the handler value"() {
        expect:
        client.get('/s404') { response { when(404) { 'gone' } } }.get(5, TimeUnit.SECONDS) == 'gone'
    }

    @Feature('response.handlers')
    def "a handler that throws fails the call with that exception"() {
        when:
        client.getSync(Object, '/s409') { response { when(409) { r -> throw new IllegalStateException("conflict ${r.problem}") } } }

        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith('conflict')
    }

    @Feature('response.handlers')
    def "inside a handler: r.problem, and r.toException() is the exception the client raises"() {
        when:
        client.getSync(Object, '/problem') { response { failure { r -> throw r.toException() } } }

        then:
        def e = thrown(HttpResponseException)
        e.method == 'GET'
        e.problem.title == 'Stale version'
        e.message.startsWith('HTTP 409 Conflict (GET ')

        expect:
        client.getSync(Object, '/problem') { response { when(409) { r -> r.problem.title } } } == 'Stale version'
        client.getSync(Object, '/ok') { response { success { r -> r.toException() } } } == null
    }

    @Feature('response.handlers')
    def "retries run first: handlers see only the final attempt"() {
        given:
        def check = server.addRequestCheck('GET', '/flaky', 503)
        int calls = 0

        when:
        def v = client.getSync(Object, '/flaky') {
            retry { maxAttempts(3); on5xx(true); backoffExponential(1, 5, false) }
            response { when(503) { r -> calls++; 'gave up' } }
        }

        then:
        v == 'gave up'
        calls == 1
        check.received.size() == 3
    }

    @Feature('response.handlers')
    def "exception { } gets transport failures and the size limit, never a status raise"() {
        given:
        int closedPort = new ServerSocket(0).withCloseable { it.localPort }
        def offline = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${closedPort}"))
        def small = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}").withOverrides(maxResponseBytes: 5L))

        expect:
        offline.getSync(Object, '/x') { response { exception { Throwable t -> "transport:${t.getClass().simpleName}".toString() } } }
                .startsWith('transport:')
        small.getSync(Object, '/ok') { response { exception { Throwable t -> t instanceof ResponseTooLargeException } } } == true

        when:
        client.getSync(Object, '/s500') { response { exception { 'never' } } }

        then:
        thrown(HttpResponseException)

        cleanup:
        offline?.close()
        small?.close()
    }

    @Feature('response.handlers')
    def "client-level defaults merge under each request's handlers, per key"() {
        given:
        def c = GroovyHttpClient.builder("http://localhost:${server.port}")
                .response {
                    success { 'default-success' }
                    failure { 'default-failure' }
                    when(404) { 'default-404' }
                }.build()

        expect:
        c.getSync(Object, '/s500') == 'default-failure'
        c.getSync(Object, '/s404') == 'default-404'
        c.getSync(Object, '/ok') { response { success { 'own-success' } } } == 'own-success'
        c.getSync(Object, '/s404') { response { when(404) { 'own-404' } } } == 'own-404'
        c.getSync(Object, '/s409') { response { when(409) { 'own-409' } } } == 'own-409'
        c.getSync(Object, '/s400') { response { when(409) { 'own-409' } } } == 'default-failure'

        cleanup:
        c?.close()
    }

    @Feature('response.handlers')
    def "when(...) refuses bad input"() {
        when:
        ResponseHandlers.from { when(404) }

        then:
        thrown(IllegalArgumentException)

        when:
        ResponseHandlers.from { when(700) { } }

        then:
        thrown(IllegalArgumentException)
    }

    @CompileStatic
    static Order staticallyCompiledCaller(GroovyHttpClient c, String path) {
        c.getSync(Order, path) {
            response {
                when(404) { Object r -> null }
                when(500..599) { Object r -> new Order(id: 'fallback') }
                success { Object r -> ((GroovyHttpClient.HttpClientResponse) r).bodyAs(Order) }
            }
        }
    }

    @Feature('response.handlers')
    def "a @CompileStatic caller compiles and runs the DSL"() {
        expect:
        staticallyCompiledCaller(client, '/ok').qty == 2
        staticallyCompiledCaller(client, '/s404') == null
        staticallyCompiledCaller(client, '/s503').id == 'fallback'
    }
}
