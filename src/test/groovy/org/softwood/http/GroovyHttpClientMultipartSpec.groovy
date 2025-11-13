package org.softwood.http

import groovy.util.logging.Slf4j
import org.softwood.http.MultipartPart
import org.softwood.test.MockHttpServer
import org.softwood.http.SecurityConfig
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Shared

/**
 * Multipart upload & file download tests for GroovyHttpClient.
 */
@Timeout(10)
@Slf4j
class GroovyHttpClientMultipartSpec extends Specification {

    MockHttpServer server
    String baseUrl
    GroovyHttpClient client

    def setup() {
        server = new MockHttpServer()
        server.init()

        baseUrl = "http://localhost:${server.port}"

        def cfg = SecurityConfig.trustAll(baseUrl)

        client = new GroovyHttpClient(cfg)

    }

    def cleanup() {
        server?.shutdown()
        client?.close()
    }

    // -------------------------------------------------------
    // 1. DSL MULTIPART
    // -------------------------------------------------------

    def "should upload multiple multipart parts using DSL"() {
        given:
        def rc = server.addRequestCheck("POST", "/upload", 200)
                .withResponseBody("OK DSL")

        when:
        def resp = client.postMultipartSync("/upload") { b ->
            b.part {
                name "file1"
                filename "readme.txt"
                content "Hello World"
            }
            b.part {
                name "file2"
                contentType "application/json"
                content '{"a":1}'
            }
            b.header("X-Test", "dsl")
        }

        then:
        resp.statusCode == 200
        resp.body == "OK DSL"
    }

    // -------------------------------------------------------
    // 2. API MULTIPART (static list of parts)
    // -------------------------------------------------------

    def "should upload multipart using postMultipartSync API"() {
        given:
        server.addRequestCheck("POST", "/upload", 200)
                .withResponseBody("Multipart API OK")

        def parts = [
                MultipartPart.text("file1", "hello"),
                MultipartPart.text("file2", "world")
        ]

        when:
        def resp = client.postMultipartSync("/upload", parts) { b ->
            b.header("X-Test", "API")
        }

        then:
        resp.statusCode == 200
        resp.body == "Multipart API OK"
    }

    // -------------------------------------------------------
    // 3. DOWNLOAD FILE
    // -------------------------------------------------------

    def "should download file as bytes"() {
        given:
        byte[] payload = "This is a test file".bytes

        server.addRequestCheck("GET", "/download", 200)
                .withResponseHeaders(["Content-Type": "application/octet-stream"])
                .withResponseBody(new String(payload))

        when:
        byte[] bytes = client.downloadBytesSync("/download")

        then:
        bytes == payload
    }

    // -------------------------------------------------------
    // 4. DOWNLOAD WITH COOKIES
    // -------------------------------------------------------

    def "should send cookies when downloading a file"() {
        given:
        server.addRequestCheck("GET", "/download", 200)
                .withExpectedCookies([session: "abc123"])
                .withResponseBody("ok")

        client.cookieHandler.addCookie("session", "abc123")

        when:
        byte[] bytes = client.downloadBytesSync("/download")

        then:
        new String(bytes) == "ok"
    }

    // -------------------------------------------------------
    // 5. MULTIPART HEADERS
    // -------------------------------------------------------

    def "should send headers with multipart request"() {
        given:
        server.addRequestCheck("POST", "/upload", 200)
                .withExpectedHeaders(["X-Mode": ["test"]])
                .withResponseBody("headers ok")

        when:
        def resp = client.postMultipartSync("/upload") { b ->
            b.part {
                name "single"
                content "hello"
            }
            b.header("X-Mode", "test")
        }

        then:
        resp.statusCode == 200
        resp.body == "headers ok"
    }
}