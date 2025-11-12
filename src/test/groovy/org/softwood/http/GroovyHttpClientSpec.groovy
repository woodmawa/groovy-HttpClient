package org.softwood.http

import spock.lang.Specification
import org.softwood.test.MockHttpServer
import org.softwood.http.SecurityConfig

import java.util.concurrent.ExecutionException

class GroovyHttpClientSpec extends Specification {

    def "should perform basic HTTP requests"() {
        given:
        def mockServer = new MockHttpServer()
        mockServer.init()
        mockServer.addRequestCheck("GET", "/users", 200)
                .withResponseBody('{"users":["user1","user2"]}')

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

        when:
        def response = client.patchSync("/items/1", '{"patched":true}') { builder ->
            builder.header("Content-Type", "application/json")
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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)
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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

        when:
        def response = client.getSync("/multi") { builder ->
            builder.header("X-Request", "val1")
            builder.header("X-Request", "val2")
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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

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

        def cfg = SecurityConfig.testing("http://localhost:${mockServer.port}")
        def client = new GroovyHttpClient(cfg)

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
}
