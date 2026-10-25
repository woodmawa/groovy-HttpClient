package org.softwood.http.security

import org.softwood.http.features.Feature

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

    @Feature('security.tls-trust')
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
    @Feature('security.profiles')
    def "SEC-2 an explicit false for #option is honoured via #route"() {
        expect:
        cfg.call(base)."$option" == false

        where:
        option           | route            | cfg
        'allowRedirects' | 'Builder'        | { String b -> GroovyHttpClient.builder(b).allowRedirects(false).build().config }
        'enableMetrics'  | 'Builder'        | { String b -> GroovyHttpClient.builder(b).enableMetrics(false).build().config }
        'allowRedirects' | 'withOverrides'  | { String b -> SecurityConfig.production('https://api.example.com').withOverrides([allowRedirects: false]) }
    }

    @Feature('redirect.follow')
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

    @Feature('redirect.allowed-hosts')
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

    @Feature('redirect.allowed-hosts')
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
    // SEC-3 / SEC-12 -- behaviour of the library-managed redirect follower (2.0.0)
    // ------------------------------------------------------------------------------------------

    @Feature('redirect.follow')
    def "SEC-3 a redirect loop stops at maxRedirects"() {
        given:
        route('/loop') { ex -> reply(ex, 302, '', [Location: '/loop']) }
        def client = GroovyHttpClient.builder(base).maxRedirects(3).build()

        when:
        client.get('/loop').get(10, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof GroovyHttpClient.RedirectRejectedException
        hits['/loop'].get() == 4   // the original request + 3 followed hops

        cleanup:
        client?.close()
    }

    @Unroll
    @Feature('redirect.credential-strip')
    def "SEC-12 #header is #outcome on a #kind redirect"() {
        given:
        String seen = null
        String targetHost = kind == 'cross-origin' ? '127.0.0.1' : 'localhost'
        route('/start') { ex -> reply(ex, 302, '', [Location: "http://${targetHost}:${port}/landing".toString()]) }
        route('/landing') { ex -> seen = ex.requestHeaders.getFirst(header); reply(ex, 200, 'ok') }
        def client = GroovyHttpClient.builder(base).allowedHosts(['localhost', '127.0.0.1'] as Set).build()
        client.setHeader(header, 'secret-value')

        when:
        client.getSync('/start')

        then:
        (seen == 'secret-value') == kept

        cleanup:
        client?.close()

        where:
        header          | kind           | kept
        'Authorization' | 'cross-origin' | false
        'Cookie'        | 'cross-origin' | false
        'Authorization' | 'same-origin'  | true
        'X-Trace-Id'    | 'cross-origin' | true

        outcome = kept ? 'kept' : 'stripped'
    }

    @Unroll
    @Feature('redirect.follow')
    def "SEC-3 a #status after POST is followed as #expectedMethod"() {
        given:
        String method = null
        String body = null
        route('/submit') { ex -> reply(ex, status, '', [Location: '/done']) }
        route('/done') { ex ->
            method = ex.requestMethod
            body = ex.requestBody.getText('UTF-8')
            reply(ex, 200, 'ok')
        }
        def client = GroovyHttpClient.builder(base).build()

        when:
        client.postSync('/submit', 'payload')

        then:
        method == expectedMethod
        body == expectedBody

        cleanup:
        client?.close()

        where:
        status | expectedMethod | expectedBody
        303    | 'GET'          | ''
        302    | 'GET'          | ''
        307    | 'POST'         | 'payload'
        308    | 'POST'         | 'payload'
    }

    // ------------------------------------------------------------------------------------------
    // SEC-4 (MEDIUM, resilience correctness) -- status-code retry is dead code
    // ------------------------------------------------------------------------------------------

    @Feature('resilience.retry')
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

    @Feature('response.max-bytes')
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
    @Feature('request.multipart')
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

    @Feature('request.cookies')
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

    @Feature('resilience.circuit-breaker')
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

    @Feature('client.lifecycle')
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
    // SEC-10 (MEDIUM) -- non-idempotent requests are not retried unless the policy opts in
    // ------------------------------------------------------------------------------------------

    @Unroll
    @Feature('resilience.retry')
    def "SEC-10 a POST that gets a 503 is retried only with retryNonIdempotent (#optIn)"() {
        given:
        def calls = new AtomicInteger()
        route('/order') { ex -> calls.incrementAndGet() == 1 ? reply(ex, 503, 'busy') : reply(ex, 201, 'created') }
        def policy = new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(3)
            backoffExponential(10, 20, false)
            on5xx(true)
            retryNonIdempotent(optIn)
        }.build()
        def client = GroovyHttpClient.builder(base).retryPolicy(policy).build()

        when:
        def outcome = outcomeOf { client.postSync('/order', '{"sku":1}') }

        then:
        hits['/order'].get() == expectedHits
        optIn ? (outcome.statusCode == 201) : (outcome instanceof GroovyHttpClient.HttpResponseException && outcome.statusCode == 503)

        cleanup:
        client?.close()

        where:
        optIn | expectedHits
        false | 1
        true  | 2
    }

    // ------------------------------------------------------------------------------------------
    // SEC-11 (LOW) -- response bodies stay out of exception messages
    // ------------------------------------------------------------------------------------------

    @Feature('response.raise-for-status')
    def "SEC-11 HttpResponseException keeps the body as a field, not in the message"() {
        given:
        def body = ('x' * 500) + 'SECRET-TOKEN-abc'
        route('/err') { ex -> reply(ex, 500, body) }
        def client = GroovyHttpClient.builder(base).build()

        when:
        client.get('/err').get(5, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        def hre = e.cause as GroovyHttpClient.HttpResponseException
        hre.statusCode == 500
        hre.responseBody == body
        !hre.message.contains('SECRET-TOKEN')
        hre.message.length() < 250

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // C-1 -- per-request DSL settings reach the wire
    // ------------------------------------------------------------------------------------------

    @Feature('request.timeout')
    def "C-1 a per-request timeout overrides the configured request timeout"() {
        given:
        route('/slow') { ex -> Thread.sleep(3000); reply(ex, 200, 'late') }
        def client = GroovyHttpClient.builder(base).requestTimeout(java.time.Duration.ofSeconds(30)).build()

        when:
        long t0 = System.nanoTime()
        client.get('/slow') { timeout(java.time.Duration.ofMillis(300)) }.get(10, TimeUnit.SECONDS)

        then:
        def e = thrown(ExecutionException)
        e.cause instanceof java.net.http.HttpTimeoutException
        (System.nanoTime() - t0) < 2_500_000_000L

        cleanup:
        client?.close()
    }

    @Feature('request.sync')
    def "sync methods throw the underlying failure, not ExecutionException"() {
        given:
        route('/missing') { ex -> reply(ex, 404, 'nope') }
        route('/slow') { ex -> Thread.sleep(1500); reply(ex, 200, 'late') }
        def client = GroovyHttpClient.builder(base).build()

        when:
        def notFound = outcomeOf { client.getSync('/missing') }
        def timedOut = outcomeOf { client.getSync('/slow') { timeout(java.time.Duration.ofMillis(200)) } }

        then:
        notFound instanceof GroovyHttpClient.HttpResponseException
        (notFound as GroovyHttpClient.HttpResponseException).statusCode == 404
        timedOut instanceof java.net.http.HttpTimeoutException

        cleanup:
        client?.close()
    }

    @Feature('request.dsl-validation')
    def "C-1 an unknown DSL call fails loudly instead of being silently dropped"() {
        given:
        route('/ok') { ex -> reply(ex, 200, 'ok') }
        def client = GroovyHttpClient.builder(base).build()

        when:
        client.get('/ok') { noSuchSetting(true) }

        then:
        thrown(MissingMethodException)

        cleanup:
        client?.close()
    }

    // ------------------------------------------------------------------------------------------
    // D-9 -- enableLogging logs the request line, never credentials
    // ------------------------------------------------------------------------------------------

    @Unroll
    @Feature('observability.logging')
    def "D-9 enableLogging(#enabled) logs the status line without query string or credentials"() {
        given:
        def logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GroovyHttpClient)
        def appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        route('/items') { ex -> reply(ex, 200, 'ok') }
        def client = GroovyHttpClient.builder(base).enableLogging(enabled).build()
        client.setHeader('Authorization', 'Bearer s3cr3t-value')

        when:
        client.getSync('/items?token=abc123')
        def lines = appender.list*.formattedMessage.findAll { it.contains('[GroovyHttpClient]') }

        then:
        lines.any { it.contains("GET http://localhost:${port}/items -> 200".toString()) } == enabled
        lines.every { !it.contains('abc123') && !it.contains('s3cr3t') }

        cleanup:
        logger.detachAppender(appender)
        client?.close()

        where:
        enabled << [true, false]
    }

    // ------------------------------------------------------------------------------------------
    // Guards -- pass today, must stay green through the uplift
    // ------------------------------------------------------------------------------------------

    @Feature('response.xml')
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

    @Feature('security.allowed-hosts')
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

    @Feature('security.allowed-hosts')
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

    @Feature(['security.profiles', 'security.tls-trust'])
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
