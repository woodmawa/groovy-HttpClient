package org.softwood.http.security

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.softwood.http.GroovyHttpClient
import org.softwood.http.MultipartPart
import org.softwood.http.SecurityConfig
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security review 2026-10-24 (baseline 1.0.2) -- confirmed-red specifications.
 *
 * SEC-n features state the behaviour the library SHOULD have. On the 1.0.2 baseline each one
 * fails, so it is marked @PendingFeature: the suite stays green while the defect is open, and
 * the feature turns RED the moment a fix makes it pass -- remove the annotation as part of that fix.
 *
 * SEC-G* features are guards that already pass. They must stay green through the
 * Groovy 6 / Java uplift and the HTTP/3 work.
 *
 * Uses a raw com.sun.net.httpserver server so each feature controls status codes,
 * redirects and streaming exactly; MockHttpServer serves one fixed response per path.
 */
@Timeout(30)
class SecurityReviewSpec extends Specification {

    HttpServer server
    int port
    String base
    final Map<String, AtomicInteger> hits = [:].withDefault { new AtomicInteger() }

    def setup() {
        // Bind IPv4 loopback explicitly; "localhost" resolves to it by default on the JVM
        // (java.net.preferIPv6Addresses=false), and 127.0.0.1 is then a DIFFERENT host name
        // for the allowedHosts checks in SEC-3.
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName('127.0.0.1'), 0), 0)
        port = server.address.port
        base = "http://localhost:${port}"
        server.start()
    }

    def cleanup() {
        server?.stop(0)
    }

    private void route(String path, Closure handler) {
        server.createContext(path) { HttpExchange ex ->
            hits[path].incrementAndGet()
            try {
                handler.call(ex)
            } finally {
                ex.close()
            }
        }
    }

    private static void reply(HttpExchange ex, int status, String body = '', Map<String, String> headers = [:]) {
        byte[] b = body.getBytes('UTF-8')
        headers.each { k, v -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length)
        if (b.length) {
            ex.responseBody.write(b)
        }
    }

    private static Object outcomeOf(Closure c) {
        try {
            return c.call()
        } catch (Throwable t) {
            return t
        }
    }

    // ------------------------------------------------------------------------------------------
    // SEC-1 (CRITICAL) -- the one-arg constructor silently disables TLS verification
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-1: GroovyHttpClient(String) delegates to SecurityConfig.testing() -> trust-all TLS')
    def "SEC-1 the String constructor must not disable TLS certificate verification"() {
        given: 'the constructor every README / asciidoc quick-start example uses'
        def client = new GroovyHttpClient('https://api.example.com')

        expect:
        !client.config.insecureAllowed

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-2 (HIGH) -- boolean options defaulting to true can never be switched off
    // ------------------------------------------------------------------------------------------

    @Unroll
    @PendingFeature(reason = 'SEC-2: SecurityConfig uses (x ?: true) so an explicit false is coerced back to true')
    def "SEC-2 an explicit false for #option is honoured via #route"() {
        expect:
        cfg.call(base)."$option" == false

        where:
        option           | route            | cfg
        'allowRedirects' | 'Builder'        | { String b -> GroovyHttpClient.builder(b).allowRedirects(false).build().config }
        'enableMetrics'  | 'Builder'        | { String b -> GroovyHttpClient.builder(b).enableMetrics(false).build().config }
        'allowRedirects' | 'withOverrides'  | { String b -> SecurityConfig.production('https://api.example.com').withOverrides([allowRedirects: false]) }
    }

    @PendingFeature(reason = 'SEC-2: allowRedirects(false) is ignored, so a 302 is followed')
    def "SEC-2 allowRedirects(false) returns the 3xx instead of following it"() {
        given:
        route('/start') { ex -> reply(ex, 302, '', [Location: '/target']) }
        route('/target') { ex -> reply(ex, 200, 'followed') }
        def client = GroovyHttpClient.builder(base).allowRedirects(false).build()

        when:
        def resp = client.getSync('/start')

        then:
        resp.statusCode == 302
        hits['/target'].get() == 0

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-3 (HIGH) -- redirects bypass the allowedHosts SSRF guard
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-3: redirects are followed by the JDK client; allowedHosts is only checked on the initial URI')
    def "SEC-3 a redirect to a host outside allowedHosts is not followed"() {
        given:
        route('/start') { ex -> reply(ex, 302, '', [Location: "http://127.0.0.1:${port}/other".toString()]) }
        route('/other') { ex -> reply(ex, 200, 'reached a host that was never allowed') }
        def client = GroovyHttpClient.builder(base).allowedHosts(['localhost'] as Set).build()

        when:
        outcomeOf { client.getSync('/start') }

        then:
        hits['/other'].get() == 0

        cleanup:
        client?.close()
    }

    def "SEC-3 control: the same redirect IS followed when the target host is allowed"() {
        given: 'proves the red case above fails for the policy reason, not because 127.0.0.1 is unreachable'
        route('/start') { ex -> reply(ex, 302, '', [Location: "http://127.0.0.1:${port}/other".toString()]) }
        route('/other') { ex -> reply(ex, 200, 'ok') }
        def client = GroovyHttpClient.builder(base).allowedHosts(['localhost', '127.0.0.1'] as Set).build()

        when:
        def resp = client.getSync('/start')

        then:
        resp.statusCode == 200
        hits['/other'].get() == 1

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-4 (MEDIUM, resilience correctness) -- status-code retry is dead code
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-4: terminal stage throws HttpResponseException for >=400 before the status-based retry sees the response')
    def "SEC-4 on5xx retry actually retries a 503"() {
        given:
        def calls = new AtomicInteger()
        route('/flaky') { ex -> calls.incrementAndGet() == 1 ? reply(ex, 503, 'busy') : reply(ex, 200, 'ok') }
        def policy = new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(3)
            backoffExponential(10, 20, false)
            on5xx(true)
        }.build()
        def client = GroovyHttpClient.builder(base).retryPolicy(policy).build()

        when:
        def resp = client.getSync('/flaky')

        then:
        resp.statusCode == 200
        hits['/flaky'].get() == 2

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-5 (HIGH, DoS) -- maxResponseBytes is checked only after the whole body is buffered
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-5: BodyHandlers.ofByteArray buffers the full body before the size check')
    def "SEC-5 a response larger than maxResponseBytes is aborted while streaming"() {
        given: 'a server that streams without end, throttled so the test cannot exhaust memory'
        route('/endless') { ex ->
            ex.sendResponseHeaders(200, 0)
            byte[] chunk = new byte[8192]
            try {
                while (true) {
                    ex.responseBody.write(chunk)
                    ex.responseBody.flush()
                    Thread.sleep(5)
                }
            } catch (IOException | InterruptedException ignored) {
                // client went away -- expected
            }
        }
        def client = GroovyHttpClient.builder(base).maxResponseBytes(64 * 1024).build()

        when:
        client.get('/endless').get(8, TimeUnit.SECONDS)

        then: 'rejected as too large, rather than hanging until the caller gives up'
        def e = thrown(ExecutionException)
        e.cause instanceof GroovyHttpClient.HttpResponseException
        (e.cause as GroovyHttpClient.HttpResponseException).statusCode == 413

        cleanup:
        client?.close()
        server?.stop(0)
    }

    // ------------------------------------------------------------------------------------------
    // SEC-6 (MEDIUM) -- multipart header injection through name / filename / content type
    // ------------------------------------------------------------------------------------------

    @Unroll
    @PendingFeature(reason = 'SEC-6: MultipartPart values are interpolated raw into Content-Disposition / Content-Type')
    def "SEC-6 a CR or LF in multipart #field is rejected"() {
        when:
        MultipartPart.bytes(name, filename, contentType, 'x'.getBytes('UTF-8'))

        then:
        thrown(IllegalArgumentException)

        where:
        field         | name                       | filename                                         | contentType
        'filename'    | 'file'                     | 'a.txt"\r\nContent-Type: text/html\r\n\r\n<x>'   | 'text/plain'
        'name'        | 'f"\r\nX-Injected: 1'      | 'a.txt'                                          | 'text/plain'
        'contentType' | 'file'                     | 'a.txt'                                          | 'text/plain\r\nX-Injected: 1'
    }

    // ------------------------------------------------------------------------------------------
    // SEC-7 (MEDIUM) -- cookie value injection
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-7: cookie(name, value) concatenates raw, so ; smuggles extra cookies')
    def "SEC-7 a cookie value containing ; is rejected"() {
        given:
        route('/c') { ex -> reply(ex, 200, 'ok') }
        def client = new GroovyHttpClient(SecurityConfig.testing(base))

        when:
        client.getSync('/c') { cookie('session', 'abc; admin=true') }

        then:
        thrown(IllegalArgumentException)

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-8 (MEDIUM) -- HALF_OPEN admits every caller, not a single probe
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-8: tryAcquirePermission returns true for every caller while HALF_OPEN')
    def "SEC-8 HALF_OPEN admits exactly one probe"() {
        given:
        def cb = new GroovyHttpClient.CircuitBreaker(1, 50)
        cb.recordFailure()
        Thread.sleep(120)

        expect:
        cb.tryAcquirePermission()
        !cb.tryAcquirePermission()
    }

    // ------------------------------------------------------------------------------------------
    // SEC-9 (LOW, resource hygiene) -- close() leaves the JDK HttpClient running
    // ------------------------------------------------------------------------------------------

    @PendingFeature(reason = 'SEC-9: close() shuts the executor but never closes the java.net.http.HttpClient')
    def "SEC-9 close() terminates the underlying HttpClient"() {
        given:
        route('/ok') { ex -> reply(ex, 200, 'ok') }
        def client = new GroovyHttpClient(SecurityConfig.testing(base))
        client.getSync('/ok')

        when:
        client.close()

        then:
        (client.@httpClient as java.net.http.HttpClient).isTerminated()
    }

    // ------------------------------------------------------------------------------------------
    // Guards -- pass today, must stay green through the uplift
    // ------------------------------------------------------------------------------------------

    def "SEC-G1 XML responses with a DOCTYPE / external entity are refused (XXE)"() {
        given:
        def xxe = '<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hosts">]><r>&x;</r>'
        route('/xml') { ex -> reply(ex, 200, xxe, ['Content-Type': 'application/xml']) }
        def client = new GroovyHttpClient(SecurityConfig.testing(base))

        when:
        client.getSync('/xml').xml

        then:
        thrown(Exception)

        cleanup:
        client?.close()
    }

    def "SEC-G2 an absolute URL to a host outside allowedHosts is refused"() {
        given:
        def client = GroovyHttpClient.builder(base)
                .allowAbsoluteUrls(true)
                .allowedHosts(['localhost'] as Set)
                .build()

        when:
        client.get('http://169.254.169.254/latest/meta-data/')

        then:
        thrown(IllegalArgumentException)

        cleanup:
        client?.close()
    }

    def "SEC-G3 absolute URLs are refused by default"() {
        given:
        def client = GroovyHttpClient.builder(base).build()

        when:
        client.get('http://localhost:1/x')

        then:
        thrown(IllegalArgumentException)

        cleanup:
        client?.close()
    }

    def "SEC-G4 the production profile verifies certificates and host names"() {
        given:
        def cfg = SecurityConfig.production('https://api.example.com')

        expect:
        !cfg.insecureAllowed
        cfg.enforceHostnameVerification
        !cfg.allowAbsoluteUrls
        cfg.allowedTlsProtocols == ['TLSv1.3']
    }
}
