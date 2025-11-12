package org.softwood.http

import org.softwood.test.MockHttpServer
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.util.concurrent.TimeUnit

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
                    .withDelay(100) // Simulate network latency
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