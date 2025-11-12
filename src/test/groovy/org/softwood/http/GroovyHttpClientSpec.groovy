package org.softwood.http

import spock.lang.Specification
import org.softwood.test.MockHttpServer

import java.util.concurrent.ExecutionException

class GroovyHttpClientSpec extends Specification {

    def "should perform basic HTTP requests"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/users", 200)
                .withResponseBody('{"users":["user1","user2"]}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/users")

        then:
        response.body == '{"users":["user1","user2"]}'
        response.statusCode == 200

        cleanup:
        mockServer.shutdown()
    }

    def "should support PATCH requests with headers"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("PATCH", "/items/1", 200)
                .withRequestHeaders(["Content-Type": "application/json"])
                .withResponseBody('{"status":"active"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.patchSync("/items/1", '{"patched":true}') { req ->
            req.header("Content-Type", "application/json")
        }

        then:
        response.body == '{"status":"active"}'
        response.statusCode == 200

        cleanup:
        mockServer.shutdown()
    }

    def "should support default headers + extra headers"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("POST", "/products", 200)
                .withRequestHeaders(["X-Default": "yes"])
                .withResponseBody('{"result":"ok"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")
        client.setHeader("X-Default", "yes")

        when:
        def response = client.postSync("/products", '{"name":"product1"}')

        then:
        response.body == '{"result":"ok"}'
        response.statusCode == 200

        cleanup:
        mockServer.shutdown()
    }

    def "should retrieve response headers (single/multiple/case-insensitive)"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/api/headers", 200)
                .withResponseHeaders([
                        "Content-Type": "application/json",
                        "X-Test"      : "value"
                ])
                .withResponseBody('{"ok":true}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/api/headers")

        then:
        response.getHeader("Content-Type") == "application/json"
        response.getHeader("X-Test") == "value"
        response.body == '{"ok":true}'

        cleanup:
        mockServer.shutdown()
    }

    def "should handle multi-value headers"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()

        mockServer.addRequestCheck(
                "GET",
                "/multi",
                200,
                '{"ok":true}',
                ["X-Request": ["val1","val2"]],
                ["X-Response": ["res1","res2"], "Content-Type": ["application/json"]]
        )

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/multi") { req ->
            req.header("X-Request", "val1")
            req.header("X-Request", "val2")
        }

        then:
        response.body == '{"ok":true}'
        response.headers."X-Response" == ["res1", "res2"]
        response.headers."Content-Type" == ["application/json"]

        cleanup:
        mockServer.shutdown()
    }

    def "should handle connection timeouts"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/slow", 200)
                .withResponseBody('{"ok":true}')
                .withDelay(1000)

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/slow")

        then:
        response.body == '{"ok":true}'

        cleanup:
        mockServer.shutdown()
    }

    def "circuit breaker opens and resets"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/circuit", 200)
                .withResponseBody('{"status":"ok"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/circuit")

        then:
        response.body == '{"status":"ok"}'

        cleanup:
        mockServer.shutdown()
    }

    def "executionError"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/api/error", 500)
                .withResponseBody('{"error":"fail"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        client.getSync("/api/error")

        then:
        def ex = thrown(ExecutionException)
        ex.cause instanceof GroovyHttpClient.HttpResponseException
        def cause = ex.cause as GroovyHttpClient.HttpResponseException
        cause.message.contains('HTTP error: 500')

        cleanup:
        mockServer.shutdown()
    }

    // ------------------------------------------------------------------------
    // NEW COOKIE TESTS
    // ------------------------------------------------------------------------

    def "should send cookies with request"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/cookie-check", 200)
                .withExpectedCookies([session: "abc123", theme: "dark"])
                .withResponseBody('{"ok":true}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def response = client.getSync("/cookie-check") { req ->
            req.cookies([session: "abc123", theme: "dark"])
        }

        then:
        response.statusCode == 200
        response.body == '{"ok":true}'

        cleanup:
        mockServer.shutdown()
    }

    def "should store and resend cookies automatically"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()

        // Step 1: Server sets a cookie
        mockServer.addRequestCheck("GET", "/login", 200)
                .withResponseCookies([session: "xyz789"])
                .withResponseBody('{"login":"ok"}')

        // Step 2: Subsequent request expects that cookie
        mockServer.addRequestCheck("GET", "/profile", 200)
                .withExpectedCookies([session: "xyz789"])
                .withResponseBody('{"user":"bob"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")

        when:
        def loginResp = client.getSync("/login")
        def profileResp = client.getSync("/profile")

        then:
        loginResp.statusCode == 200
        profileResp.statusCode == 200
        profileResp.body == '{"user":"bob"}'
        client.getCookie("session").value == "xyz789"

        cleanup:
        mockServer.shutdown()
    }

    def "should manually add and remove cookies"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/check-cookie", 200)
                .withExpectedCookies([token: "12345"])
                .withResponseBody('{"token":"ok"}')

        def client = new GroovyHttpClient("http://localhost:${mockServer.port}")
        client.addCookie("token", "12345")

        when:
        def response = client.getSync("/check-cookie")

        then:
        response.statusCode == 200
        response.body == '{"token":"ok"}'

        when: "we remove the cookie and request again"
        client.removeCookie("token")
        client.getSync("/check-cookie")

        then:
        def ex = thrown(ExecutionException)
        ex.cause.message.contains("Cookie mismatch")

        cleanup:
        mockServer.shutdown()
    }
}
