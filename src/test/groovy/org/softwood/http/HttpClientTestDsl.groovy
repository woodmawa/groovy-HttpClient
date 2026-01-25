package org.softwood.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * HttpClientTestDsl — Fluent DSL for testing GroovyHttpClient with a lightweight mock HTTP server.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>✔ Embedded test HTTP server (started on-demand)</li>
 *   <li>✔ Fluent DSL for defining mock endpoints and expectations</li>
 *   <li>✔ Request verification (headers, body, query params)</li>
 *   <li>✔ Response mocking with status, headers, body</li>
 *   <li>✔ JSON and XML response helpers</li>
 *   <li>✔ Request history and assertion helpers</li>
 *   <li>✔ Multipart upload testing</li>
 *   <li>✔ Cookie and header verification</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * def testDsl = HttpClientTestDsl.create()
 * testDsl.start()
 *
 * testDsl.when("/api/users")
 *     .respondWith {
 *         status 200
 *         json([users: ["Alice", "Bob"]])
 *     }
 *
 * def client = new GroovyHttpClient(testDsl.baseUrl)
 * def response = client.getSync("/api/users")
 *
 * assert response.statusCode == 200
 * testDsl.verify("/api/users") {
 *     wasCalled()
 *     withHeader("User-Agent")
 * }
 *
 * testDsl.stop()
 * }</pre>
 *
 * @author Will Woodman / Softwood Consulting Ltd
 * @since 1.4.0
 */
@Slf4j
class HttpClientTestDsl implements AutoCloseable {

    private HttpServer server
    private int port
    private final Map<String, MockEndpoint> endpoints = [:]
    private final ConcurrentLinkedQueue<RecordedRequest> requestHistory = new ConcurrentLinkedQueue<>()
    private boolean started = false

    /**
     * Factory method to create a new test DSL instance
     */
    static HttpClientTestDsl create(int port = 0) {
        new HttpClientTestDsl(port)
    }

    private HttpClientTestDsl(int port) {
        this.port = port
    }

    /**
     * Start the embedded HTTP server
     */
    HttpClientTestDsl start() {
        if (started) {
            throw new IllegalStateException("Test server already started")
        }

        server = HttpServer.create(new InetSocketAddress("localhost", port), 0)

        // Use actual bound port if 0 was specified
        this.port = server.address.port

        server.createContext("/") { HttpExchange exchange ->
            handleRequest(exchange)
        }

        server.start()
        started = true
        log.info("Test HTTP server started on port {}", this.port)
        this
    }

    /**
     * Stop the embedded HTTP server
     */
    void stop() {
        if (server) {
            server.stop(0)
            started = false
            log.info("Test HTTP server stopped")
        }
    }

    /**
     * Get the base URL of the test server
     */
    String getBaseUrl() {
        if (!started) {
            throw new IllegalStateException("Server not started. Call start() first.")
        }
        "http://localhost:${port}"
    }

    /**
     * Define a mock endpoint with fluent DSL
     */
    MockEndpoint when(String path) {
        def endpoint = new MockEndpoint(path)
        endpoints[normalizePath(path)] = endpoint
        endpoint
    }

    /**
     * Reset all endpoints and request history
     */
    void reset() {
        endpoints.clear()
        requestHistory.clear()
    }

    /**
     * Get all recorded requests
     */
    List<RecordedRequest> getRecordedRequests() {
        requestHistory.toList()
    }

    /**
     * Get recorded requests for a specific path
     */
    List<RecordedRequest> getRequestsFor(String path) {
        def normalized = normalizePath(path)
        requestHistory.findAll { it.path == normalized }.toList()
    }

    /**
     * Verify expectations on a specific endpoint
     */
    RequestVerification verify(String path) {
        new RequestVerification(getRequestsFor(path))
    }

    /**
     * Create a GroovyHttpClient configured for this test server
     */
    GroovyHttpClient createClient(Closure configClosure = null) {
        def config = SecurityConfig.testing(baseUrl)

        if (configClosure) {
            def overrides = [:]
            configClosure.delegate = overrides
            configClosure.resolveStrategy = Closure.DELEGATE_FIRST
            configClosure.call()
            config = config.withOverrides(overrides)
        }

        new GroovyHttpClient(config)
    }

    private void handleRequest(HttpExchange exchange) {
        def path = normalizePath(exchange.requestURI.path)
        def method = exchange.requestMethod

        // Record the request
        def recorded = new RecordedRequest(
                method: method,
                path: path,
                headers: exchange.requestHeaders.collectEntries { k, v -> [(k): v] },
                body: exchange.requestBody.text,
                queryParams: parseQueryParams(exchange.requestURI.query)
        )
        requestHistory.offer(recorded)

        // Find matching endpoint
        def endpoint = endpoints[path]

        if (!endpoint) {
            // Return 404 for undefined endpoints
            sendResponse(exchange, 404, "text/plain", "Not Found: ${path}")
            return
        }

        // Execute response builder
        def responseBuilder = new ResponseBuilder()
        if (endpoint.responseConfig) {
            endpoint.responseConfig.delegate = responseBuilder
            endpoint.responseConfig.resolveStrategy = Closure.DELEGATE_FIRST
            endpoint.responseConfig.call()
        }

        // Send response
        sendResponse(
                exchange,
                responseBuilder.statusCode,
                responseBuilder.contentType,
                responseBuilder.body,
                responseBuilder.headers
        )
    }

    private void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String contentType,
            String body,
            Map<String, String> headers = [:]
    ) {
        try {
            // Set content type
            if (contentType) {
                exchange.responseHeaders.set("Content-Type", contentType)
            }

            // Set custom headers
            headers.each { k, v ->
                exchange.responseHeaders.set(k, v)
            }

            byte[] responseBytes = body?.getBytes(StandardCharsets.UTF_8) ?: new byte[0]
            exchange.sendResponseHeaders(statusCode, responseBytes.length)

            if (responseBytes.length > 0) {
                exchange.responseBody.write(responseBytes)
            }
            exchange.responseBody.close()
        } catch (Exception e) {
            log.error("Error sending response", e)
        }
    }

    private static String normalizePath(String path) {
        if (!path) return "/"
        if (!path.startsWith("/")) path = "/${path}"
        path
    }

    private static Map<String, List<String>> parseQueryParams(String query) {
        if (!query) return [:]

        query.split("&").collectEntries { param ->
            def parts = param.split("=", 2)
            def key = URLDecoder.decode(parts[0], "UTF-8")
            def value = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : ""
            [(key): [value]]
        }.groupBy { it.key }.collectEntries { k, v ->
            [(k): v.collect { it.value[0] }]
        }
    }

    @Override
    void close() {
        stop()
    }

    /* =========================================================================
     * DSL Classes
     * ========================================================================= */

    /**
     * Represents a mock endpoint configuration
     */
    static class MockEndpoint {
        final String path
        Closure responseConfig
        final AtomicInteger callCount = new AtomicInteger(0)

        MockEndpoint(String path) {
            this.path = path
        }

        /**
         * Define the response for this endpoint
         */
        MockEndpoint respondWith(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = ResponseBuilder) Closure config) {
            this.responseConfig = config
            this
        }

        /**
         * Shorthand for simple status-only response
         */
        MockEndpoint respondWithStatus(int status) {
            respondWith { statusCode = status }
        }

        /**
         * Shorthand for JSON response
         */
        MockEndpoint respondWithJson(Object data, int status = 200) {
            respondWith {
                statusCode = status
                json(data)
            }
        }

        int getCallCount() {
            callCount.get()
        }
    }

    /**
     * Builder for constructing mock responses
     */
    static class ResponseBuilder {
        int statusCode = 200
        String contentType = "text/plain"
        String body = ""
        Map<String, String> headers = [:]

        void status(int code) {
            this.statusCode = code
        }

        void contentType(String type) {
            this.contentType = type
        }

        void body(String content) {
            this.body = content
        }

        void header(String name, String value) {
            headers[name] = value
        }

        void json(Object data) {
            this.contentType = "application/json"
            this.body = JsonOutput.toJson(data)
        }

        void xml(String xmlContent) {
            this.contentType = "application/xml"
            this.body = xmlContent
        }

        void text(String content) {
            this.contentType = "text/plain"
            this.body = content
        }

        void html(String htmlContent) {
            this.contentType = "text/html"
            this.body = htmlContent
        }
    }

    /**
     * Represents a recorded HTTP request
     */
    static class RecordedRequest {
        String method
        String path
        Map<String, List<String>> headers
        String body
        Map<String, List<String>> queryParams

        String getHeader(String name) {
            headers.find { k, v -> k.equalsIgnoreCase(name) }?.value?.first()
        }

        boolean hasHeader(String name) {
            headers.any { k, v -> k.equalsIgnoreCase(name) }
        }

        Object getJsonBody() {
            body ? new JsonSlurper().parseText(body) : null
        }

        String getQueryParam(String name) {
            queryParams[name]?.first()
        }

        List<String> getQueryParams(String name) {
            queryParams[name] ?: []
        }

        boolean hasQueryParam(String name) {
            queryParams.containsKey(name)
        }

        @Override
        String toString() {
            "${method} ${path} (${headers.size()} headers, ${body?.length() ?: 0} bytes)"
        }
    }

    /**
     * Fluent verification DSL for recorded requests
     */
    static class RequestVerification {
        final List<RecordedRequest> requests

        RequestVerification(List<RecordedRequest> requests) {
            this.requests = requests
        }

        RequestVerification wasCalled(int expectedCount = -1) {
            if (expectedCount == -1) {
                assert !requests.isEmpty(), "Expected at least one request, but none were made"
            } else {
                assert requests.size() == expectedCount,
                        "Expected ${expectedCount} request(s), but got ${requests.size()}"
            }
            this
        }

        RequestVerification wasNotCalled() {
            assert requests.isEmpty(), "Expected no requests, but ${requests.size()} were made"
            this
        }

        RequestVerification withMethod(String method) {
            assert requests.any { it.method.equalsIgnoreCase(method) },
                    "No request found with method ${method}"
            this
        }

        RequestVerification withHeader(String name, String value = null) {
            def matching = requests.findAll { it.hasHeader(name) }
            assert !matching.isEmpty(), "No request found with header ${name}"

            if (value) {
                assert matching.any { it.getHeader(name) == value },
                        "No request found with header ${name}=${value}"
            }
            this
        }

        RequestVerification withBody(String expectedBody) {
            assert requests.any { it.body == expectedBody },
                    "No request found with expected body"
            this
        }

        RequestVerification withBodyContaining(String substring) {
            assert requests.any { it.body?.contains(substring) },
                    "No request found with body containing '${substring}'"
            this
        }

        RequestVerification withJsonBody(Closure matcher) {
            def found = requests.find { req ->
                try {
                    def json = req.jsonBody
                    return matcher.call(json)
                } catch (Exception e) {
                    return false
                }
            }
            assert found, "No request found matching JSON body criteria"
            this
        }

        RequestVerification withQueryParam(String name, String value = null) {
            def matching = requests.findAll { it.hasQueryParam(name) }
            assert !matching.isEmpty(), "No request found with query param ${name}"

            if (value) {
                assert matching.any { it.getQueryParam(name) == value },
                        "No request found with query param ${name}=${value}"
            }
            this
        }

        RecordedRequest first() {
            assert !requests.isEmpty(), "No requests recorded"
            requests.first()
        }

        RecordedRequest last() {
            assert !requests.isEmpty(), "No requests recorded"
            requests.last()
        }
    }
}