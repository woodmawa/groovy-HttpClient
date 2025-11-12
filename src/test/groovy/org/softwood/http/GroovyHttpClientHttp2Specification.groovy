package org.softwood.http

import org.softwood.test.MockHttpServer
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

class GroovyHttpClientHttp2Spec extends Specification {

    MockHttpServer mockServer
    GroovyHttpClient client

    def setup() {
        mockServer = new MockHttpServer()
        mockServer.init()
        client = new GroovyHttpClient("http://localhost:${mockServer.port}")
    }

    def cleanup() {
        mockServer.shutdown()
        client.close()
    }

    def "should use HTTP/2 protocol for GET requests"() {
        given:
        mockServer.addRequestCheck("GET", "/version", 200)
                .withResponseBody("Success")

        when:
        def response = client.getSync("/version") { req ->
            req.header("Accept", "application/json")
        }

        then:
        response.body.trim() == "Success"
        response.statusCode == 200
    }

    @Unroll
    def "should handle HTTP/2 #method requests correctly"() {
        given:
        mockServer.addRequestCheck(method, "/test", 200)
                .withResponseBody("Success with $method")

        when:
        def response = executeRequest(method, "/test")

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

    def "should handle HTTP/2 multiplexing with concurrent requests"() {
        given:
        def paths = (1..5).collect { "/concurrent$it" }
        paths.each { path ->
            mockServer.addRequestCheck("GET", path, 200)
                    .withResponseBody("Response for $path")
                    .withDelay(100)
        }

        when:
        def futures = paths.collect { path -> client.get(path) }
        def responses = futures.collect {
            it.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
        }

        then:
        responses.size() == paths.size()
        responses.every { it.body.startsWith("Response for /concurrent") }
    }

    // ---------------------------------------------------------------
    // NEW TEST: Concurrent HTTP/2 requests with cookie isolation
    // ---------------------------------------------------------------
    def "should maintain independent cookies across concurrent HTTP/2 requests"() {
        given:
        def users = ["alice", "bob", "charlie"]

        // Each login endpoint sets a unique cookie for its user
        users.each { user ->
            mockServer.addRequestCheck("GET", "/login/${user}", 200)
                    .withResponseCookies([session: "${user}-token"])
                    .withResponseBody("{\"login\":\"${user}\"}")

            mockServer.addRequestCheck("GET", "/profile/${user}", 200)
                    .withExpectedCookies([session: "${user}-token"])
                    .withResponseBody("{\"user\":\"${user}\"}")
        }

        when:
        // Each user gets an independent client (isolated cookie store)
        def clients = users.collect { new GroovyHttpClient("http://localhost:${mockServer.port}") }

        // Step 1: concurrent logins
        def loginFutures = []
        users.eachWithIndex { user, i ->
            loginFutures << clients[i].get("/login/${user}")
        }
        def loginResponses = loginFutures.collect {
            it.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
        }

        // Step 2: concurrent profile requests (reuse cookies)
        def profileFutures = []
        users.eachWithIndex { user, i ->
            profileFutures << clients[i].get("/profile/${user}")
        }
        def profileResponses = profileFutures.collect {
            it.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
        }

        then:
        loginResponses.size() == users.size()
        profileResponses.every { it.statusCode == 200 }
        profileResponses*.body.sort() == ['{"user":"alice"}', '{"user":"bob"}', '{"user":"charlie"}']

        and:
        // ✅ Verify that each client's cookie store contains the correct session cookie
        users.eachWithIndex { user, i ->
            def cookies = clients[i].cookieStore.cookies
            def sessionCookie = cookies.find { it.name == "session" }
            assert sessionCookie?.value == "${user}-token"
        }

        cleanup:
        clients.each { it.close() }
    }

    //negative cookie handling test
    def "should reject requests with mismatched cookies"() {
        given: "a mock server expecting a specific cookie value"
        mockServer.addRequestCheck("GET", "/secure", 200)
                .withExpectedCookies(["auth": "abc123"])
                .withResponseBody('{"ok":true}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")
        client.addCookie("auth", "wrongValue")

        when: "sending a request with incorrect cookie"
        client.getSync("/secure")

        then: "the server rejects it with an error"
        def ex = thrown(ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        def cause = ex.cause as GroovyHttpClient.HttpResponseException
        cause.statusCode == 400
        cause.message.contains("Cookie mismatch")

        cleanup:
        mockServer.shutdown()
    }

    @Unroll
    def "should reject requests when cookie is #scenario"() {
        given: "a mock server expecting a valid auth cookie"
        mockServer.addRequestCheck("GET", "/secure", 200)
                .withExpectedCookies(["auth": "abc123"])
                .withResponseBody('{"ok":true}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        and: "optionally add an incorrect cookie if specified"
        if (cookieToSend) {
            client.addCookie("auth", cookieToSend)
        }

        when: "sending the request"
        client.getSync("/secure")

        then: "the server rejects it with a 400 and proper error details"
        def ex = thrown(ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        def cause = ex.cause as GroovyHttpClient.HttpResponseException
        cause.statusCode == 400
        cause.message.contains("Cookie mismatch")

        cleanup:
        mockServer.shutdown()

        where:
        scenario         | cookieToSend
        "missing cookie" | null
        "wrong cookie"   | "badValue"
    }

    def "should clear all cookies when clearCookies() is called"() {
        given: "a mock server that expects a cookie initially"
        mockServer.addRequestCheck("GET", "/cookie-test", 200)
                .withExpectedCookies(["auth": "xyz"])
                .withResponseBody('{"ok":true}')

        and: "a client that has a cookie pre-set"
        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")
        client.addCookie("auth", "xyz")

        when: "we perform a request with the cookie present"
        def response1 = client.getSync("/cookie-test")

        then: "the request succeeds because the cookie is correct"
        response1.statusCode == 200
        response1.body == '{"ok":true}'
        client.cookieStore.cookies.size() == 1

        when: "we clear cookies and send the same request again"
        client.clearCookies()
        client.getSync("/cookie-test")

        then: "the server rejects it due to missing cookie"
        def ex = thrown(java.util.concurrent.ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        def cause = ex.cause as GroovyHttpClient.HttpResponseException
        cause.statusCode == 400
        cause.message.contains("Cookie mismatch")

        cleanup:
        mockServer.shutdown()
        client.close()
    }

    // ------------------------------------------------------------------------
    // Helper method
    // ------------------------------------------------------------------------
    private GroovyHttpClient.HttpClientResponse executeRequest(String method, String path) {
        switch (method) {
            case "GET":
                return client.getSync(path)
            case "POST":
                return client.postSync(path, "test body")
            case "PUT":
                return client.putSync(path, "test body")
            case "DELETE":
                return client.deleteSync(path)
            case "PATCH":
                return client.patchSync(path, "test body")
            default:
                throw new IllegalArgumentException("Unsupported method: $method")
        }
    }
}
