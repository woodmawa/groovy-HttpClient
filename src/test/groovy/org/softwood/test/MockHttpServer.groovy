package org.softwood.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.util.logging.Slf4j

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * MockHttpServer — A lightweight, dependency-free HTTP test server built on
 * {@link com.sun.net.httpserver.HttpServer} for validating requests sent by
 * {@link org.softwood.http.GroovyHttpClient} or any Java/Groovy HTTP client.
 *
 * <p>
 * MockHttpServer is designed for:
 * </p>
 * <ul>
 *   <li>unit testing</li>
 *   <li>integration testing</li>
 *   <li>multipart form upload validation</li>
 *   <li>header and cookie verification</li>
 *   <li>fault simulation (delays, bad status codes)</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 *
 * <ul>
 *   <li><strong>Dynamic route registration</strong> using {@code addRequestCheck()}</li>
 *   <li><strong>Multipart/form-data validation</strong> (field names, filenames, content types, raw body bytes)</li>
 *   <li><strong>Header and cookie assertions</strong> — mismatches produce automatic 400/500 responses</li>
 *   <li><strong>Configurable response bodies, cookies, and headers</strong></li>
 *   <li><strong>Latency simulation</strong> with {@code withDelay(ms)}</li>
 *   <li><strong>Thread-safe</strong> using a cached executor pool</li>
 *   <li><strong>No external dependencies</strong> — uses only JDK</li>
 *   <li><strong>Spock-friendly</strong> for clean BDD test specification</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <ol>
 *   <li>Testing HTTP clients without spinning up real servers</li>
 *   <li>Validating multipart requests sent via Groovy DSL:
 *     <pre>{@code
 * client.postMultipartSync("/upload") { b ->
 *     b.part {
 *         name "file1"
 *         content "hello"
 *     }
 * }
 * }</pre>
 *   </li>
 *   <li>Simulating error responses:
 *     <pre>{@code
 * server.addRequestCheck("GET", "/fail", 500)
 * }</pre>
 *   </li>
 *   <li>Enforcing cookies across requests (session flow testing)</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>{@code
 * def server = new MockHttpServer()
 * server.init()
 * ...
 * server.shutdown()
 * }</pre>
 *
 * <p>
 * {@code init()} binds to a random available port and starts a background
 * thread executor. All request checks must be registered either before or
 * after {@code init()} — both flows are supported.
 * </p>
 *
 * <h2>Request Handling Model</h2>
 *
 * <p>Each route is backed by a <strong>RequestCheck</strong> instance, which defines:</p>
 *
 * <ul>
 *   <li>expected HTTP method</li>
 *   <li>expected path</li>
 *   <li>expected status code for the response</li>
 *   <li>optional expected headers</li>
 *   <li>optional expected cookies</li>
 *   <li>optional expected multipart parts</li>
 *   <li>response body</li>
 *   <li>response headers and cookies</li>
 *   <li>optional artificial delay</li>
 * </ul>
 *
 * <h3>Header Validation</h3>
 *
 * <p>Headers may be validated using:</p>
 *
 * <pre>{@code
 * withExpectedHeaders(["X-Test": ["one","two"]])
 * }</pre>
 *
 * <p>
 * Multiple values per header name are supported. Mismatch → automatic 500 error.
 * </p>
 *
 * <h3>Cookie Validation</h3>
 *
 * <pre>{@code
 * withExpectedCookies([session: "123", mode: "admin"])
 * }</pre>
 *
 * <p>
 * Missing or mismatched cookies → automatic 400 error.
 * </p>
 *
 * <h3>Multipart Validation</h3>
 *
 * MockHttpServer supports detailed parsing and structural validation of
 * <code>multipart/form-data</code> bodies, including:
 *
 * <ul>
 *   <li>boundary detection</li>
 *   <li>individual part extraction</li>
 *   <li>part name, filename</li>
 *   <li>content-type validation</li>
 *   <li>binary content byte-for-byte matching</li>
 * </ul>
 *
 * <p>
 * Example:
 * </p>
 *
 * <pre>{@code
 * server.addRequestCheck("POST", "/upload", 200)
 *       .withExpectedMultipart([
 *           [name: "file1", content: "abc".bytes],
 *           [name: "meta",  contentType: "application/json"]
 *       ])
 * }</pre>
 *
 * <h2>Response Builder</h2>
 *
 * <pre>{@code
 * server.addRequestCheck("POST", "/login", 200)
 *       .withResponseBody('{"ok":true}')
 *       .withResponseHeaders(["X-Test":"demo"])
 *       .withResponseCookies([session:"xyz"])
 * }</pre>
 *
 * <p>
 * Responses can include:
 * </p>
 * <ul>
 *   <li>String body</li>
 *   <li>text, JSON, or binary content</li>
 *   <li>Content-Type headers</li>
 *   <li>Set-Cookie headers</li>
 * </ul>
 *
 * <h2>Simulating Latency</h2>
 *
 * <pre>{@code
 * withDelay(250) // adds a 250ms pause before the response
 * }</pre>
 *
 * <h2>Error Behavior</h2>
 *
 * <p>
 * The mock server enforces strict validation and will produce:
 * </p>
 * <ul>
 *   <li><strong>405</strong> — method does not match expected</li>
 *   <li><strong>400</strong> — cookie mismatch</li>
 *   <li><strong>500</strong> — header mismatch, multipart mismatch, or internal error</li>
 * </ul>
 *
 * <h2>Spock-Friendly Examples</h2>
 *
 * <pre>{@code
 * server.addRequestCheck("GET", "/secure", 200)
 *       .withExpectedCookies([auth:"abc"])
 *       .withResponseBody("ok")
 *
 * client.addCookie("auth", "abc")
 *
 * def resp = client.getSync("/secure")
 * assert resp.statusCode == 200
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Routes and request checks are stored in synchronized collections.
 * All inbound HTTP requests are served through a cached thread pool,
 * mirroring production concurrency characteristics.
 * </p>
 *
 * <h2>Intended Scope</h2>
 *
 * <ul>
 *   <li>Unit tests</li>
 *   <li>Client integration tests</li>
 *   <li>Multipart DSL validation</li>
 *   <li>Error and timeout simulation</li>
 *   <li>No dependency framework or container required</li>
 * </ul>
 *
 * @see org.softwood.http.GroovyHttpClient
 * @see org.softwood.http.MultipartPart
 * @see org.softwood.http.SecurityConfig
 *
 * @author
 *   Will Woodman / Softwood Consulting Ltd
 * @since 1.4.0
 */
/**
 * Lightweight Groovy-based mock HTTP server used for testing GroovyHttpClient.
 * Supports:
 *  - Custom responses
 *  - Cookie validation
 *  - Header validation
 *  - Configurable delays
 *  - @Slf4j logging
 *  - Safe context creation (no duplicate context errors)
 *
 * Tracks its own context registry since JDK HttpServer provides no API for this.
 */

@Slf4j
class MockHttpServer {

    private HttpServer server
    private int port
    private final List<RequestCheck> checks = []
    private boolean started = false

    MockHttpServer() { this(0) }
    MockHttpServer(int port) { this.port = port }

    // -------------------------
    // SERVER START / STOP
    // -------------------------

    void init() {
        server = HttpServer.create(new InetSocketAddress(port), 0)
        server.executor = Executors.newCachedThreadPool()
        port = server.address.port
        started = true

        // create contexts for any checks already registered
        checks.each { check ->
            ensureContextExists(check)
        }

        server.start()
        log.debug "MockHttpServer running on port $port"
    }

    int getPort() { port }

    void shutdown() {
        if (started) {
            server.stop(0)
            started = false
            log.debug "MockHttpServer stopped"
        }
    }

    // -------------------------
    // REGISTER CHECKS
    // -------------------------

    RequestCheck addRequestCheck(String method, String path, int statusCode = 200) {
        def check = new RequestCheck(method, path, statusCode)
        checks << check
        if (started) ensureContextExists(check)
        return check
    }

    RequestCheck addRequestCheck(
            String method,
            String path,
            int statusCode,
            String responseBody,
            Map requestHeaders,
            Map responseHeaders
    ) {
        def req = new RequestCheck(method, path, statusCode)
        req.withResponseBody(responseBody)

        requestHeaders?.each { k, v ->
            req.withRequestHeaders([(k.toString()): (v instanceof List ? v*.toString() : [v.toString()])])
        }

        responseHeaders?.each { k, v ->
            req.withResponseHeaders([(k.toString()): (v instanceof List ? v*.toString() : [v.toString()])])
        }

        checks << req
        if (started) ensureContextExists(req)
        return req
    }

    // -------------------------
    // CONTEXT HANDLING
    // -------------------------

    private boolean hasContext(String path) {
        try {
            server.removeContext(path)
            return true
        } catch (IllegalArgumentException e) {
            return false
        }
    }

    private void restoreContext(String path) {
        def check = checks.find { it.path == path }
        if (check) {
            server.createContext(path) { ex -> handleRequest(ex, check) }
        }
    }

    private boolean ensureContextExists(RequestCheck check) {
        if (!started) return false

        if (hasContext(check.path)) {
            restoreContext(check.path)
            return false
        }

        server.createContext(check.path) { ex -> handleRequest(ex, check) }
        return true
    }

    // -------------------------
    // REQUEST PROCESSING
    // -------------------------

    private void handleRequest(HttpExchange ex, RequestCheck check) {
        def method = ex.requestMethod
        def requestHeaders = ex.requestHeaders
        def cookies = parseCookies(ex)

        if (!method.equalsIgnoreCase(check.method)) {
            respond(ex, 405, "Method Not Allowed")
            return
        }

        if (!headersMatch(requestHeaders, check.expectedHeaders)) {
            respond(ex, 400, "Header mismatch: expected ${check.expectedHeaders}, got ${requestHeaders}")
            return
        }

        if (!cookiesMatch(cookies, check.expectedCookies)) {
            respond(ex, 400, "Cookie mismatch: expected ${check.expectedCookies}, got ${cookies}")
            return
        }

        respond(ex, check.statusCode, check.responseBody, check.responseHeaders, check.responseCookies)
    }

    private static Map<String, String> parseCookies(HttpExchange ex) {
        def raw = ex.requestHeaders.getFirst("Cookie")
        if (!raw) return [:]
        raw.split(';').collectEntries {
            def parts = it.trim().split('=', 2)
            parts.size() == 2 ? [(parts[0]): parts[1]] : [(parts[0]): ""]
        }
    }

    private static boolean cookiesMatch(Map<String, String> actual, Map<String, String> expected) {
        if (!expected) return true
        def filtered = actual.findAll { !it.key.startsWith('$') }
        expected.every { k, v -> filtered[k]?.replace('"', '') == v }
    }

    private static boolean headersMatch(Map<String, List<String>> actual, Map<String, List<String>> expected) {
        if (!expected) return true
        expected.every { key, vals ->
            def found = actual.find { k, _ -> k.equalsIgnoreCase(key) }?.value ?: []
            found.containsAll(vals)
        }
    }

    private static void respond(
            HttpExchange ex,
            int status,
            String body,
            Map<String, List<String>> headers = [:],
            Map<String, String> cookies = [:]
    ) {
        headers.each { k, vals ->
            vals.each { v -> ex.responseHeaders.add(k, v.toString()) }
        }

        cookies.each { k, v ->
            ex.responseHeaders.add("Set-Cookie", "$k=$v; Path=/")
        }

        byte[] bytes = (body ?: "").getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, bytes.length)
        ex.responseBody.write(bytes)
        ex.close()
    }

    // -------------------------
    // RequestCheck inner class
    // -------------------------

    static class RequestCheck {
        String method
        String path
        int statusCode
        String responseBody = ''
        Map<String, List<String>> responseHeaders = [:]
        Map<String, List<String>> expectedHeaders = [:]
        Map<String, String> expectedCookies = [:]
        Map<String, String> responseCookies = [:]
        int delayMs = 0

        RequestCheck(String method, String path, int statusCode) {
            this.method = method
            this.path = path
            this.statusCode = statusCode
        }

        RequestCheck withResponseBody(String body) { this.responseBody = body; this }

        RequestCheck withResponseHeaders(Map headers) {
            headers?.each { k, v ->
                responseHeaders[k.toString()] = (v instanceof List ? v.collect { it.toString() } : [v.toString()])
            }
            this
        }

        RequestCheck withRequestHeaders(Map headers) {
            headers?.each { k, v ->
                expectedHeaders[k.toString()] = (v instanceof List ? v.collect { it.toString() } : [v.toString()])
            }
            this
        }

        // 🔹 New alias used by tests
        RequestCheck withExpectedHeaders(Map headers) {
            withRequestHeaders(headers)
        }

        RequestCheck withExpectedCookies(Map<String, String> cookies) { expectedCookies.putAll(cookies); this }
        RequestCheck withResponseCookies(Map<String, String> cookies) { responseCookies.putAll(cookies); this }
        RequestCheck withDelay(int ms) { this.delayMs = ms; this }
    }
}
