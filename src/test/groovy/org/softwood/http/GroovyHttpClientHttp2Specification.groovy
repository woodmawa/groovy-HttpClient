package org.softwood.http

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll


import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration

class GroovyHttpClientHttp2Spec extends Specification {
    // Remove @Shared - we want a fresh server and client for each test
    Http2TestServer testServer
    SSLContext sslContext // Make SSLContext an instance field

    @Shared
    GroovyHttpClient client

    /*
     * Create a ClosureServlet wrapper that takes a closure.
     *  Use new ClosureServlet({ req, resp -> ... }) whenever you were calling addServlet("/path", closure).
     */
    static class ClosureServlet extends HttpServlet {
        private final Closure handler

        ClosureServlet(Closure handler) {
            this.handler = handler
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {
            handler(req, resp)
        }
    }

    def setupSpec() {
        // --- trust all certs for testing ---
        TrustManager[] trustAllCerts = [
                new X509TrustManager() {
                    X509Certificate[] getAcceptedIssuers() { null }
                    void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        ] as TrustManager[]

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, trustAllCerts, new SecureRandom())

        client = new GroovyHttpClient(
                "https://dummy-host-for-tests",  //will be be updated in tests
                GroovyHttpClient.DEFAULT_CONNECT_TIMEOUT,
                GroovyHttpClient.DEFAULT_REQUEST_TIMEOUT,
                GroovyHttpClient.DEFAULT_FAILURE_THRESHOLD,
                GroovyHttpClient.DEFAULT_RESET_TIMEOUT_MS,
                sslContext,
                null
        )
    }

    def cleanupSpec() {

        //wireMockServer?.stop()
        client?.close()
    }

    def setup () {
        // Initialize the server here so each test gets a clean start and a new port
        testServer = new Http2TestServer()
    }

    def cleanup() {
         testServer?.stop()
    }

    /** ---tests ---
     * start a fresh testServer in each test in the given : block
     * calculate the baseUrl using testServer.port
     * pass the full url
     * client is thread safe and immutable
     */
    def "should use HTTP/2 protocol for GET requests"() {
        given:
        testServer.addServlet(new ClosureServlet({ req, resp ->
            resp.writer.println("Success")
        }), "/version")
        testServer.startServer()
        testServer.waitUntilListening()

        // Update client with actual port
        def baseUrl = "https://localhost:${testServer.port}"

        when:
        def response = client.getSync("${baseUrl}/version") { -> header("Accept", "application/json") }

        then:
        response.body.trim() == "Success"
        response.statusCode == 200
    }

    @Unroll
    def "should handle HTTP/2 #method requests correctly"() {
        given:
        testServer.addServlet(new ClosureServlet({ req, resp ->
            resp.writer.println("Success with $method")
        }), "/test")
        testServer.startServer()
        testServer.waitUntilListening()

        // Create the full URL to use in the request
        def absoluteUrl = "https://localhost:${testServer.port}/test"

        when:
        def response = executeRequest(method, absoluteUrl)

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
            testServer.addServlet(new ClosureServlet({ req, resp ->
                Thread.sleep(100)
                resp.writer.println("Response for $path")
            }), path)
        }
        testServer.startServer()
        testServer.waitUntilListening()

        def baseUrl = "https://localhost:${testServer.port}"

        when:
        def futures = paths.collect { path -> client.get("$baseUrl${path}") }
        def responses = futures.collect { it.get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) }

        then:
        responses.size() == paths.size()
        responses.every { it.body.startsWith("Response for /concurrent") }
    }

    //helper method for should handle HTTP/2 #method requests correctly
    //now response wrapped in HttpClientResponse
    private GroovyHttpClient.HttpClientResponse executeRequest(String method, String url) {
        switch (method) {
            case "GET":
                // Pass the full URL
                return client.getSync(url)
            case "POST":
                return client.postSync(url, "test body")
            case "PUT":
                return client.putSync(url, "test body")
            case "DELETE":
                return client.deleteSync(url)
            case "PATCH":
                return client.patchSync(url, "test body")
            default:
                throw new IllegalArgumentException("Unsupported method: $method")
        }
    }

}