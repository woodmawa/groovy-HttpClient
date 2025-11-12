package org.softwood.http

import spock.lang.Specification
import spock.lang.Unroll
import org.softwood.test.MockHttpServer
import org.softwood.http.SecurityConfig

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class GroovyHttpClientHttp2Spec extends Specification {

    MockHttpServer mockServer

    def setup() {
        mockServer = new MockHttpServer()
        mockServer.init()
    }

    def cleanup() {
        mockServer?.shutdown()
    }

    def "should use HTTP/2-like client for GET requests"() {
        given:
        mockServer.addRequestCheck("GET", "/version", 200)
                .withResponseBody("Success")

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

        when:
        def response = client.getSync("/version") { builder ->
            builder.header("Accept", "application/json")
        }

        then:
        response.body.trim() == "Success"
        response.statusCode == 200
    }

    @Unroll
    def "should handle #method requests correctly"() {
        given:
        mockServer.addRequestCheck(method, "/test", 200)
                .withResponseBody("Success with $method")

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

        when:
        def response = executeRequest(client, method, "/test")

        then:
        response.body.trim() == "Success with $method"
        response.statusCode == 200

        where:
        method   | _
        "GET"    | _
        "POST"   | _
        "PUT"    | _
        "DELETE" | _
        "PATCH"  | _
    }

    def "should handle multiplexed concurrent requests"() {
        given:
        def paths = (1..5).collect { "/concurrent$it" }
        paths.each { path ->
            mockServer.addRequestCheck("GET", path, 200)
                    .withResponseBody("Response for $path")
                    .withDelay(100)
        }

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

        when:
        def futures = paths.collect { path -> client.get(path) }
        def responses = futures.collect {
            it.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
        }

        then:
        responses.size() == paths.size()
        responses.every { it.body.startsWith("Response for /concurrent") }
    }

    def "should maintain independent cookies across concurrent requests"() {
        given:
        def users = ["alice", "bob", "charlie"]

        users.each { user ->
            mockServer.addRequestCheck("GET", "/${user}", 200)
                    .withExpectedCookies(["session": "${user}-token"])
                    .withResponseCookies(["session": "${user}-token"])
                    .withResponseBody("OK for $user")
        }

        def clients = users.collect { user ->
            def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
            def c = new GroovyHttpClient(cfg)
            c.addCookie("session", "${user}-token")
            [user, c]
        }

        when:
        def futures = clients.collect { pair ->
            def (user, c) = pair
            c.get("/${user}")
        }

        def responses = futures.collect {
            it.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
        }

        then:
        responses.size() == users.size()
        responses.every { it.statusCode == 200 }
        responses.eachWithIndex { resp, i ->
            def user = users[i]
            def cookies = clients[i][1].getCookies()
            assert cookies.any { it.value == "${user}-token" }
        }
    }

    def "should reject mismatched cookies"() {
        given:
        mockServer.addRequestCheck("GET", "/secure", 200)
                .withExpectedCookies(["session": "expected-token"])
                .withResponseBody("OK")

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)
        client.addCookie("session", "wrong-token")

        when:
        client.getSync("/secure")

        then:
        def ex = thrown(ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        ex.cause.message.contains("HTTP error: 400")
    }

    def "should clear cookies and enforce rejection after removal"() {
        given:
        mockServer.addRequestCheck("GET", "/cookie-test", 200)
                .withExpectedCookies(["auth": "xyz"])
                .withResponseBody('{"ok":true}')

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)
        client.addCookie("auth", "xyz")

        when: "first request succeeds"
        def response1 = client.getSync("/cookie-test")

        then:
        response1.statusCode == 200
        response1.body == '{"ok":true}'
        client.cookieStore.cookies.size() == 1

        when: "cookies are cleared"
        client.clearCookies()
        client.getSync("/cookie-test")

        then: "should fail after clearing cookies"
        def ex = thrown(ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        ex.cause.message.contains("Cookie mismatch")

        cleanup:
        mockServer.shutdown()
        client.close()
    }

    // helper
    private GroovyHttpClient.HttpClientResponse executeRequest(GroovyHttpClient client, String method, String path) {
        switch (method) {
            case "GET": return client.getSync(path)
            case "POST": return client.postSync(path, "test body")
            case "PUT": return client.putSync(path, "test body")
            case "DELETE": return client.deleteSync(path)
            case "PATCH": return client.patchSync(path, "test body")
            default: throw new IllegalArgumentException("Unsupported method: $method")
        }
    }
}
