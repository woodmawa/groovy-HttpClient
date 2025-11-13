package org.softwood.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.util.logging.Slf4j

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * MockHttpServer.groovy
 * ------------------------------------------------------------
 * A lightweight, embeddable HTTP mock server designed for unit and
 * integration testing of GroovyHttpClient and related HTTP components.
 *
 * <p>Provides a declarative API for defining mock endpoints with:</p>
 * <ul>
 *   <li>Expected request methods, paths, headers, and cookies</li>
 *   <li>Configurable response status, headers, and bodies</li>
 *   <li>Response delay simulation for timeout testing</li>
 *   <li>Automatic request validation and diagnostic logging</li>
 *   <li>Configurable delays </li>
 *   <li>Slf4j logging with logback </li>
 *   <li>Safe context creation (no duplicate context errors)</li>
 * </ul>
 *
 * <p>Used by the GroovyHttpClient test suite for validating correctness,
 * concurrency handling, and cookie persistence.</p>
 *
 * @author  Will Woodman
 * @version 1.0-RELEASE
 * @since   2025-11
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
