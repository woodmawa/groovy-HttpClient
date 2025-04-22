package org.softwood.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.common.JettySettings
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import wiremock.org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory
import wiremock.org.eclipse.jetty.server.HttpConfiguration
import wiremock.org.eclipse.jetty.server.HttpConnectionFactory
import wiremock.org.eclipse.jetty.server.SecureRequestCustomizer
import wiremock.org.eclipse.jetty.server.Server
import wiremock.org.eclipse.jetty.server.ServerConnector
import wiremock.org.eclipse.jetty.server.SslConnectionFactory
import wiremock.org.eclipse.jetty.util.ssl.SslContextFactory

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
    @Shared
    WireMockServer wireMockServer

    @Shared
    GroovyHttpClient client

    def setupSpec() {

        // Set system properties for HTTP/2 and ALPN before starting WireMock
        System.setProperty("jetty.alpn.protocols", "h2,http/1.1")
        System.setProperty("https.protocols", "TLSv1.3,TLSv1.2")
        System.setProperty("jetty.http.port", "0")
        System.setProperty("jetty.ssl.port", "8443")
        System.setProperty("jetty.http2.enabled", "true")
        System.setProperty("wiremock.jetty.alpn", "true")

        // Enable HTTP/2 using the withJettySettings method
        // Create WireMock configuration with HTTP/2 enabled
        WireMockConfiguration config = WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath("src/test/resources/keystore.jks")
                .keystorePassword("password")
                .trustStorePath("src/test/resources/keystore.jks")
                .trustStorePassword("password")
                .needClientAuth(false)
                .enableBrowserProxying(false)

        // Configure WireMock with HTTP/2 support
        wireMockServer = new WireMockServer(config)

        wireMockServer.start()

        println "Started WireMock server on HTTPS port: ${wireMockServer.httpsPort()}"

        // Create a trust manager that trusts all certificates for testing
        TrustManager[] trustAllCerts = [
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        ] as TrustManager[]

        // Create a hostname verifier that accepts all hostnames for testing
        HostnameVerifier trustAllHostnames = new HostnameVerifier() {
            @Override
            boolean verify(String hostname, SSLSession session) {
                return true
            }
        }

        // Create an SSL context that trusts all certificates
        SSLContext sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, new SecureRandom())

        // Install the all-trusting trust manager
        SSLContext.setDefault(sslContext)

        // Set default hostname verifier globally (for legacy Java APIs)
        //HttpsURLConnection.setDefaultHostnameVerifier(trustAllHostnames)


        // Create client with the WireMock server URL
        client = new GroovyHttpClient("https://localhost:${wireMockServer.httpsPort()}",
                GroovyHttpClient.DEFAULT_CONNECT_TIMEOUT,
                GroovyHttpClient.DEFAULT_REQUEST_TIMEOUT,
                GroovyHttpClient.DEFAULT_FAILURE_THRESHOLD,
                GroovyHttpClient.DEFAULT_RESET_TIMEOUT_MS,
                sslContext,
                null)
    }

    def cleanupSpec() {

        wireMockServer?.stop()
        client?.close()
    }

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
}