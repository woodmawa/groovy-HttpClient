package org.softwood.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MockHttpServer {

    private HttpServer server
    private int port
    private List<RequestCheck> checks = []
    private boolean started = false

    MockHttpServer() { this(0) }

    MockHttpServer(int port) { this.port = port }

    void init() {
        server = HttpServer.create(new InetSocketAddress(port), 0)
        server.executor = Executors.newCachedThreadPool()
        port = server.address.port
        started = true

        checks.each { check ->
            server.createContext(check.path) { HttpExchange exchange ->
                handleRequest(exchange, check)
            }
        }

        server.start()
        println "MockHttpServer running on port $port"
    }

    int getPort() { port }

    void shutdown() {
        if (started && server) {
            server.stop(0)
            started = false
            println "MockHttpServer stopped"
        }
    }

    RequestCheck addRequestCheck(String method, String path, int statusCode = 200) {
        def check = new RequestCheck(method, path, statusCode)
        checks << check

        if (started) {
            server.createContext(path) { HttpExchange exchange ->
                handleRequest(exchange, check)
            }
        }
        return check
    }

    RequestCheck addRequestCheck(String method, String path, int statusCode, String responseBody, Map requestHeaders, Map responseHeaders) {
        Map<String, List<String>> reqHeaders = [:]
        requestHeaders?.each { k, v ->
            reqHeaders[k.toString()] = (v instanceof List ? v.collect { it.toString() } : [v.toString()])
        }

        Map<String, List<String>> respHeaders = [:]
        responseHeaders?.each { k, v ->
            respHeaders[k.toString()] = (v instanceof List ? v.collect { it.toString() } : [v.toString()])
        }

        def check = new RequestCheck(method, path, statusCode)
        check.withResponseBody(responseBody)
        check.withRequestHeaders(reqHeaders)
        check.withResponseHeaders(respHeaders)
        checks << check

        if (started) {
            server.createContext(path) { HttpExchange exchange ->
                handleRequest(exchange, check)
            }
        }
        return check
    }

    private void handleRequest(HttpExchange exchange, RequestCheck check) {
        def requestMethod = exchange.requestMethod
        def requestHeaders = exchange.requestHeaders
        def cookies = parseCookies(exchange)

        if (!requestMethod.equalsIgnoreCase(check.method)) {
            respond(exchange, 405, "Method Not Allowed")
            return
        }

        if (!headersMatch(requestHeaders, check.expectedHeaders)) {
            respond(exchange, 500, "Header mismatch: expected ${check.expectedHeaders}, got ${requestHeaders}")
            return
        }

        if (!cookiesMatch(cookies, check.expectedCookies)) {
            respond(exchange, 400, "Cookie mismatch: expected ${check.expectedCookies}, got ${cookies}")
            return
        }

        respond(exchange, check.statusCode, check.responseBody, check.responseHeaders, check.responseCookies)
    }

    private static Map<String, String> parseCookies(HttpExchange exchange) {
        def cookieHeader = exchange.requestHeaders.getFirst("Cookie")
        if (!cookieHeader) return [:]
        cookieHeader.split(';').collectEntries { pair ->
            def parts = pair.trim().split('=', 2)
            if (parts.size() == 2) [(parts[0]): parts[1]] else [(parts[0]): ""]
        }
    }

    //make the matching less strict by ignoring $variables
    private static boolean cookiesMatch(Map<String, String> actual, Map<String, String> expected) {
        if (!expected || expected.isEmpty()) return true

        // Filter out special attributes like $Version, $Path, $Domain
        def filteredActual = actual.findAll { k, v -> !k.startsWith('$') }

        expected.every { k, v ->
            def actualVal = filteredActual[k]
            actualVal?.replaceAll(/^\"|\"$/, '') == v  // remove any quotes from value
        }
    }

    private static boolean headersMatch(Map<String, List<String>> actual, Map<String, List<String>> expected) {
        if (!expected || expected.isEmpty()) return true
        expected.every { key, expectedVals ->
            def actualVals = actual.find { k, _ -> k.equalsIgnoreCase(key) }?.value ?: []
            actualVals.containsAll(expectedVals)
        }
    }

    private static void respond(HttpExchange exchange, int status, String body,
                                Map<String, List<String>> headers = [:],
                                Map<String, String> cookies = [:]) {
        headers?.each { k, vals ->
            (vals instanceof List ? vals : [vals]).each { v ->
                exchange.responseHeaders.add(k, v.toString())
            }
        }
        cookies?.each { name, value ->
            exchange.responseHeaders.add("Set-Cookie", "${name}=${value}; Path=/")
        }

        def bytes = (body ?: '').getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
        exchange.close()
    }

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

        RequestCheck withExpectedCookies(Map<String, String> cookies) { expectedCookies.putAll(cookies); this }
        RequestCheck withResponseCookies(Map<String, String> cookies) { responseCookies.putAll(cookies); this }
        RequestCheck withDelay(int ms) { this.delayMs = ms; this }
    }
}
