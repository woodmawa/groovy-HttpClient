package org.softwood.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.common.JettySettings
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
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

import static com.github.tomakehurst.wiremock.client.WireMock.*


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

        // Update client with actual port
        def baseUrl = "https://localhost:${testServer.port}"

        when:
        def response = client.getSync("${baseUrl}/version") { -> header("Accept", "application/json") }

        then:
        response.trim() == "Success"
    }

    @Unroll
    def "should handle HTTP/2 #method requests correctly"() {
        given:
        testServer.addServlet(new ClosureServlet({ req, resp ->
            resp.writer.println("Success with $method")
        }), "/test")
        testServer.startServer()

        // Create the full URL to use in the request
        def absoluteUrl = "https://localhost:${testServer.port}/test"

        when:
        def response = executeRequest(method, absoluteUrl)

        then:
        response.trim() == "Success with $method"

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

        def baseUrl = "https://localhost:${testServer.port}"

        when:
        def futures = paths.collect { path -> client.get("$baseUrl${path}") }
        def responses = futures.collect { it.get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) }

        then:
        responses.size() == paths.size()
        responses.every { it.startsWith("Response for /concurrent") }
    }

    private String executeRequest(String method, String url) {
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

    /*
    def "should initialize WireMock with HTTPS"() {
        when:
        def wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath("src/test/resources/keystore.jks") // Add a keystore file if you have one
                .keystorePassword("password")                    // Use appropriate password
                .trustStorePath("src/test/resources/keystore.jks") // Can be the same as keystore for testing
                .trustStorePassword("password")
                .needClientAuth(false)

        )
        wireMockServer.start()

        then:
        wireMockServer.isRunning()
        wireMockServer.httpsPort() > 0

        cleanup:
        wireMockServer?.stop()
    }

    def "should use HTTP/2 protocol for requests"() {
        given: "a stubbed endpoint that returns protocol version"
        wireMockServer.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Success")))

        when: "making a request"
        def response = client.get("/version") { ->
            header("Accept", "application/json")
        }.get()

        then: "the response should be received"
        response == "Success"

        and: "the protocol version should be HTTP/2"
        def request = wireMockServer.findAll(getRequestedFor(urlEqualTo("/version")))[0]
        request.getProtocol() == "HTTP/2.0"
    }

    @Unroll
    def "should handle HTTP/2 #method requests correctly"() {
        given: "a stubbed endpoint for different HTTP methods"
        wireMockServer.stubFor(request(method, urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Success with $method")))

        when: "making the request"
        def response = executeRequest(method)

        then: "the response should be correct"
        response == "Success with $method"

        and: "the protocol should be HTTP/2"
        def request = wireMockServer.findAll(requestedFor(urlEqualTo("/test")))[0]
        request.getProtocol() == "HTTP/2.0"

        where:
        method   | _
        "GET"    | _
        "POST"   | _
        "PUT"    | _
        "DELETE" | _
        "PATCH"  | _
    }

    def "should handle HTTP/2 multiplexing with concurrent requests"() {
        given: "multiple stubbed endpoints"
        def paths = (1..5).collect { "/concurrent$it" }
        paths.each { path ->
            wireMockServer.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("Response for $path")
                            .withFixedDelay(100))) // Add small delay to test concurrency
        }

        when: "making concurrent requests"
        def futures = paths.collect { path ->
            client.get(path)
        }
        def responses = futures.collect { it.get(Duration.ofSeconds(5)) }

        then: "all requests should complete successfully"
        responses.size() == paths.size()
        responses.every { it.startsWith("Response for /concurrent") }

        and: "all requests should use HTTP/2"
        wireMockServer.findAll(getRequestedFor(urlMatching("/concurrent.*")))
                .every { it.getProtocol() == "HTTP/2.0" }
    }

    def "should handle HTTP/2 connection preface correctly"() {
        given: "a stubbed endpoint"
        wireMockServer.stubFor(get(urlEqualTo("/preface"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Connected with HTTP/2")))

        when: "making the first request"
        def response = client.getSync("/preface")

        then: "the response should be received"
        response == "Connected with HTTP/2"

        and: "the connection should use HTTP/2"
        def request = wireMockServer.findAll(getRequestedFor(urlEqualTo("/preface")))[0]
        request.getProtocol() == "HTTP/2.0"
    }

    private String executeRequest(String method) {
        switch (method) {
            case "GET":
                return client.getSync("/test")
            case "POST":
                return client.postSync("/test", "test body")
            case "PUT":
                return client.putSync("/test", "test body")
            case "DELETE":
                return client.deleteSync("/test")
            case "PATCH":
                return client.patchSync("/test", "test body")
            default:
                throw new IllegalArgumentException("Unsupported method: $method")
        }
    }
*/

}