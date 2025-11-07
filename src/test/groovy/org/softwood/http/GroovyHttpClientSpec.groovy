package org.softwood.http

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit


class GroovyHttpClientSpec extends Specification {


    @Shared
    def mockServer = new MockHttpServer()

    @Shared
    def baseUrl = "http://localhost:${mockServer.port}"

    def setupSpec() {
        mockServer.start()
    }

    def cleanupSpec() {
        mockServer.stop()
    }

    @Subject
    GroovyHttpClient client

    def setup() {
        client = new GroovyHttpClient(baseUrl)
    }

    def cleanup() {
        client.close()
    }

    def "should create client with valid URL"() {
        when:
        def client = new GroovyHttpClient(baseUrl)

        then:
        client != null
        client.host.toString() == baseUrl
    }

    def "should throw exception when invalid URL is provided"() {
        when:
        new GroovyHttpClient("invalid url")

        then:
        thrown(IllegalArgumentException)
    }

    def "should throw exception when empty URL is provided"() {
        when:
        new GroovyHttpClient("")

        then:
        thrown(IllegalArgumentException)
    }

    def "should correctly resolve URI with trailing slash in base URL"() {
        given:
        def clientWithSlash = new GroovyHttpClient("${baseUrl}/")

        when:
        def uri = clientWithSlash.resolveUri("test")

        then:
        uri.toString() == "${baseUrl}/test"

        cleanup:
        clientWithSlash.close()
    }

    def "should correctly resolve URI without trailing slash in base URL"() {
        when:
        def uri = client.resolveUri("test")

        then:
        uri.toString() == "${baseUrl}/test"
    }

    def "should correctly resolve URI with leading slash in path"() {
        when:
        def uri = client.resolveUri("/test")

        then:
        uri.toString() == "${baseUrl}/test"
    }

    def "should correctly handle empty path"() {
        when:
        def uri = client.resolveUri("")

        then:
        uri.toString() == baseUrl
    }

    def "should perform GET request successfully"() {
        given:
        mockServer.addResponse("/users", 200, '{"users": ["user1", "user2"]}')

        when:
        def response = client.get("/users").get(5, TimeUnit.SECONDS)

        then:
        response == '{"users": ["user1", "user2"]}'
    }

    def "should perform GET request with configuration"() {
        given:
        mockServer.addResponseWithHeaderCheck(
                "/products",
                200,
                '{"products": ["product1", "product2"]}',
                ["Accept": "application/json", "X-API-Key": "test-key"]
        )

        when:
        def response = client.get("/products") {
            header("Accept", "application/json")
            header("X-API-Key", "test-key")
        }.get(5, TimeUnit.SECONDS)

        then:
        response == '{"products": ["product1", "product2"]}'
    }

    def "should perform POST request with body"() {
        given:
        def body = '{"name": "New Item"}'
        mockServer.addPostRequestCheck("/items", 201, '{"id": 123}', body)

        when:
        def response = client.post("/items", body).get(5, TimeUnit.SECONDS)

        then:
        response == '{"id": 123}'
    }

    def "should perform PUT request with body"() {
        given:
        def body = '{"name": "Updated Item"}'
        mockServer.addPutRequestCheck("/items/123", 200, '{"id": 123, "updated": true}', body)

        when:
        def response = client.put("/items/123", body).get(5, TimeUnit.SECONDS)

        then:
        response == '{"id": 123, "updated": true}'
    }

    def "should perform DELETE request"() {
        given:
        mockServer.addResponse("/items/123", 204, '')
        mockServer.addDeleteResponse("/items/123", 204, '')

        when:
        def response = client.delete("/items/123").get(5, TimeUnit.SECONDS)

        then:
        response == ''
    }

    def "should throw exception for HTTP error"() {
        given:
        mockServer.addResponse("/error", 500, '{"error": "Internal Server Error"}')

        when:
        client.get("/error").get(5, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof GroovyHttpClient.HttpResponseException
        e.cause.statusCode == 500
        e.cause.message.contains("Internal Server Error")
    }

    def "should handle connection timeout"() {
        given:
        def shortTimeoutClient = new GroovyHttpClient(
                baseUrl,
                Duration.ofMillis(1) // Unrealistically short timeout to force failure
        )
        mockServer.addDelayedResponse("/delayed", 200, '{"result": "delayed"}', 100)

        when:
        shortTimeoutClient.get("/delayed").get(5, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        def rootCause = getRootCause(e)
        rootCause instanceof java.net.http.HttpTimeoutException ||
                rootCause instanceof java.net.ConnectException

        cleanup:
        shortTimeoutClient.close()
    }

    def "circuit breaker should open after threshold failures"() {
        given:
        def thresholdClient = new GroovyHttpClient(
                baseUrl,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                2, // Low threshold for testing
                30000
        )
        mockServer.addResponse("/circuit-test", 500, '{"error": "Server Error"}')

        when: "Making first failing request"
        try {
            thresholdClient.get("/circuit-test").get(5, TimeUnit.SECONDS)
        } catch (Exception ex) {
            // Expected exception
        }

        and: "Making second failing request"
        try {
            thresholdClient.get("/circuit-test").get(5, TimeUnit.SECONDS)
        } catch (Exception ex) {
            // Expected exception
        }

        and: "Making third request after circuit should be open"
        thresholdClient.get("/circuit-test").get(5, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof GroovyHttpClient.CircuitOpenException

        cleanup:
        thresholdClient.close()
    }

    def "circuit breaker should reset after timeout"() {
        given:
        def resetClient = new GroovyHttpClient(
                baseUrl,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                2, // Low threshold for testing
                100 // Very short reset timeout for testing
        )
        mockServer.addResponse("/circuit-reset-test", 500, '{"error": "Server Error"}')
        mockServer.addResponse("/circuit-success", 200, '{"status": "ok"}')

        when: "Trigger circuit breaker to open"
        try {
            resetClient.get("/circuit-reset-test").get(5, TimeUnit.SECONDS)
            resetClient.get("/circuit-reset-test").get(5, TimeUnit.SECONDS)
        } catch (Exception e) {
            println "exception e was $e.cause, and type $e.cause.class"
            assert e.cause instanceof GroovyHttpClient.HttpResponseException
        }

        and: "Verify circuit is open"
        try {
            resetClient.get("/circuit-reset-test").get(5, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            assert e.cause instanceof GroovyHttpClient.CircuitOpenException
        }

        and: "Wait for reset timeout"
        Thread.sleep(200)

        and: "Try a successful request after reset"
        def response = resetClient.get("/circuit-success").get(5, TimeUnit.SECONDS)

        then:
        response == '{"status": "ok"}'

        cleanup:
        resetClient.close()
    }

    // Helper method to get the root cause of an exception
    static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable
        while (cause.getCause() != null) {
            cause = cause.getCause()
        }
        return cause
    }

    /**
     * Simple mock HTTP server for testing HTTP client
     */
    static class MockHttpServer {
        def server
        int port = 8088
        def handlers = [:]

        void start() {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0)

            server.createContext("/", { exchange ->
                def path = exchange.requestURI.path
                def method = exchange.requestMethod
                def handler = handlers["$method:$path"]

                if (handler) {
                    handler(exchange)
                } else {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.responseBody.close()
                }
            })

            server.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            server.start()
        }

        void stop() {
            server.stop(0)
        }

        void addResponse(String path, int statusCode, String body) {
            handlers["GET:$path"] = { exchange ->
                exchange.responseHeaders.set("Content-Type", "application/json")

                // FIX: If 204 (No Content), send headers with length 0.
                // The server implementation will ignore length for 204, but we avoid writing the body.
                // For other statuses, use body.length().
                long responseLength = (statusCode == 204) ? -1 : body.length()

                exchange.sendResponseHeaders(statusCode, responseLength)

                if (body) { // Only write the body if it's not empty
                    exchange.responseBody.write(body.bytes)
                }
                exchange.responseBody.close()
            }
        }

        void addDelayedResponse(String path, int statusCode, String body, long delayMs) {
            handlers["GET:$path"] = { exchange ->
                Thread.sleep(delayMs)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(statusCode, body.length())
                exchange.responseBody.write(body.bytes)
                exchange.responseBody.close()
            }
        }

        void addResponseWithHeaderCheck(String path, int statusCode, String body, Map<String, String> expectedHeaders) {
            handlers["GET:$path"] = { exchange ->
                def allHeadersPresent = expectedHeaders.every { key, value ->
                    exchange.requestHeaders.containsKey(key) &&
                            exchange.requestHeaders.getFirst(key) == value
                }

                if (allHeadersPresent) {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(statusCode, body.length())
                    exchange.responseBody.write(body.bytes)
                } else {
                    exchange.sendResponseHeaders(400, 0)
                }
                exchange.responseBody.close()
            }
        }

        void addPostRequestCheck(String path, int statusCode, String responseBody, String expectedRequestBody) {
            handlers["POST:$path"] = { exchange ->
                def requestBody = exchange.requestBody.text

                if (requestBody == expectedRequestBody) {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(statusCode, responseBody.length())
                    exchange.responseBody.write(responseBody.bytes)
                } else {
                    exchange.sendResponseHeaders(400, 0)
                }
                exchange.responseBody.close()
            }
        }

        void addPutRequestCheck(String path, int statusCode, String responseBody, String expectedRequestBody) {
            handlers["PUT:$path"] = { exchange ->
                def requestBody = exchange.requestBody.text

                if (requestBody == expectedRequestBody) {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(statusCode, responseBody.length())
                    exchange.responseBody.write(responseBody.bytes)
                } else {
                    exchange.sendResponseHeaders(400, 0)
                }
                exchange.responseBody.close()
            }
        }

        void addDeleteResponse(String path, int statusCode, String body) {
            handlers["DELETE:$path"] = { exchange ->
                exchange.responseHeaders.set("Content-Type", "application/json")

                // FIX: If 204 (No Content), send headers with length 0.
                // The server implementation will ignore length for 204, but we avoid writing the body.
                // For other statuses, use body.length().
                long responseLength = (statusCode == 204) ? -1 : body.length()

                exchange.sendResponseHeaders(statusCode, responseLength)

                if (body) {
                    exchange.responseBody.write(body.bytes)
                }
                exchange.responseBody.close()
            }
        }
    }
}
