package org.softwood.http

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.ImmutableOptions
import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

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
 * GroovyHttpClient — A modern, lightweight HTTP client with Groovy DSL support.
 *
 * <p>
 * Built on top of Java 25’s {@link java.net.http.HttpClient} and virtual threads,
 * this client provides a fluent Groovy API for synchronous and asynchronous HTTP calls,
 * multipart uploads, resilient networking, and a secure configuration model.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>✔ Fluent DSL for headers, cookies, and request configuration</li>
 *   <li>✔ Automatic cookie management backed by {@link java.net.CookieManager}</li>
 *   <li>✔ Support for multipart/form-data via DSL or static {@code MultipartPart} API</li>
 *   <li>✔ Integrated circuit breaker for resilience (open/half-open/closed)</li>
 *   <li>✔ Virtual threads for ultra-lightweight concurrency</li>
 *   <li>✔ Works with secure defaults using {@link org.softwood.http.SecurityConfig}</li>
 *   <li>✔ SSRF-safe URL resolution with host allow-list</li>
 *   <li>✔ Async and sync API (using {@code CompletableFuture})</li>
 * </ul>
 *
 * <h2>Construction Options</h2>
 * <p>
 * The client supports multiple constructors:
 * </p>
 *
 * <ul>
 *   <li><strong>String-only</strong>: {@code new GroovyHttpClient("https://api")}</li>
 *   <li><strong>Extended constructors</strong> with timeouts, retry thresholds, SSLContext, and thread factories</li>
 *   <li><strong>SecurityConfig-based</strong>: {@code new GroovyHttpClient(SecurityConfig.production(...))}</li>
 * </ul>
 *
 * <h2>Multipart Support</h2>
 * <p>
 * Two styles are supported:
 * </p>
 * <ul>
 *   <li><strong>DSL-based</strong>:
 *     <pre>{@code
 *     client.postMultipartSync("/upload") {
 *         part {
 *             name "file1"
 *             filename "data.txt"
 *             content "Hello!"
 *         }
 *         header "X-Test", "dsl"
 *     }
 *     }</pre>
 *   </li>
 *   <li><strong>Static API</strong> via {@link org.softwood.http.MultipartPart}:
 *     <pre>{@code
 *     def parts = [
 *         MultipartPart.text("a", "alpha"),
 *         MultipartPart.binary("b", bytes)
 *     ]
 *     client.postMultipartSync("/upload", parts)
 *     }</pre>
 *   </li>
 * </ul>
 *
 * <h2>SecurityConfig Integration</h2>
 * <p>
 * A {@link org.softwood.http.SecurityConfig} may define:
 * </p>
 *
 * <ul>
 *   <li>TLS enforcement and allowed protocols</li>
 *   <li>Cookie acceptance policies</li>
 *   <li>Absolute URL and host allow-list controls (SSRF protection)</li>
 *   <li>Request/connection timeouts</li>
 *   <li>Body-size limits</li>
 *   <li>Logging and metrics options</li>
 *   <li>Retry/circuit breaker thresholds</li>
 * </ul>
 *
 * <h2>Usage with MockHttpServer</h2>
 * <p>
 * Designed to integrate tightly with {@code MockHttpServer} for Spock/JUnit test environments.
 * Multipart upload tests, cookie validation, and header inspection are fully supported.
 * </p>
 *
 * <h2>Threading Model</h2>
 * <p>
 * All work is executed using virtual threads created via:
 * </p>
 * <pre>{@code
 * Thread.ofVirtual().name("http-client-", n).factory()
 * }</pre>
 *
 * <h2>Recommended Use-Cases</h2>
 * <ul>
 *   <li>Testing HTTP integrations (with or without MockHttpServer)</li>
 *   <li>High-concurrency microservices</li>
 *   <li>Script-friendly command-and-control HTTP workloads</li>
 *   <li>Secure production usage with hardened SecurityConfig profiles</li>
 * </ul>
 *
 * <h2>Notes</h2>
 * <p>
 * This client intentionally wraps Java’s {@code HttpClient} rather than replacing it.
 * All returned response objects include:
 * </p>
 * <ul>
 *   <li>status code</li>
 *   <li>headers (Map&lt;String,List&lt;String&gt;&gt;)</li>
 *   <li>body as String</li>
 * </ul>
 *
 * @author
 *   Will Woodman / Softwood Consulting Ltd
 * @since 1.4.0
 */
/**
 * GroovyHttpClient — virtual-thread HTTP client with:
 *  - Circuit breaker
 *  - Cookie management
 *  - SecurityConfig integration
 *  - Multipart upload & file download
 *  - Groovy DSL for request configuration
 */


@Slf4j
class GroovyHttpClient implements AutoCloseable {

    enum State { CLOSED, OPEN, HALF_OPEN }

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10)
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30)
    static final Duration DEFAULT_SYNC_TIMEOUT    = Duration.ofSeconds(30)

    static final int  DEFAULT_FAILURE_THRESHOLD = 5
    static final long DEFAULT_RESET_TIMEOUT_MS  = 30_000L

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0)

    final SecurityConfig config
    private final URI host
    private final HttpClient httpClient
    private final CookieManager cookieManager
    private final CircuitBreaker circuitBreaker

    private final boolean allowAbsoluteUrls
    private final Set<String> allowedHosts
    private final long maxResponseBytes

    // Safer concurrent map for headers
    private final Map<String, List<String>> defaultHeaders =
            new ConcurrentHashMap<String, List<String>>()

    // Interceptors: each is { RequestContext ctx, Closure next -> CompletableFuture<HttpClientResponse> }
    final List<Closure> interceptors = new java.util.concurrent.CopyOnWriteArrayList<>()

    final SimpleCookieHandler cookieHandler = new SimpleCookieHandler()

    // Optional: manage executor lifecycle
    private final def executor

    /* =========================================================================
     * Builder
     * ========================================================================= */

    static class Builder {
        private String baseUrl
        private Duration connectTimeout
        private Duration requestTimeout
        private boolean insecureAllowed = false
        private boolean allowAbsoluteUrls = false
        private long maxResponseBytes = 10_000_000L
        private CookiePolicy cookiePolicy
        private Set<String> allowedHosts
        private boolean enableLogging = false
        private boolean enableMetrics = true
        private boolean allowRedirects = true
        private int failureThreshold = 5
        private long resetTimeoutMs = 30_000L
        private boolean enforceHostnameVerification = true
        private List<String> allowedTlsProtocols        // null = SecurityConfig default (TLS 1.3 + 1.2)
        private List<String> allowedCipherSuites = []
        private RetryPolicy retryPolicy
        private SSLContext sslContext
        private Integer maxRedirects

        Builder(String baseUrl) { this.baseUrl = baseUrl }

        Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; this }
        Builder requestTimeout(Duration timeout) { this.requestTimeout = timeout; this }
        Builder insecureAllowed(boolean allowed) { this.insecureAllowed = allowed; this }
        Builder allowAbsoluteUrls(boolean allowed) { this.allowAbsoluteUrls = allowed; this }
        Builder maxResponseBytes(long bytes) { this.maxResponseBytes = bytes; this }
        Builder cookiePolicy(CookiePolicy policy) { this.cookiePolicy = policy; this }
        Builder allowedHosts(Set<String> hosts) { this.allowedHosts = hosts; this }
        Builder enableLogging(boolean enabled) { this.enableLogging = enabled; this }
        Builder enableMetrics(boolean enabled) { this.enableMetrics = enabled; this }
        Builder allowRedirects(boolean allowed) { this.allowRedirects = allowed; this }
        Builder failureThreshold(int threshold) { this.failureThreshold = threshold; this }
        Builder resetTimeoutMs(long timeoutMs) { this.resetTimeoutMs = timeoutMs; this }
        Builder enforceHostnameVerification(boolean enforce) { this.enforceHostnameVerification = enforce; this }
        Builder allowedTlsProtocols(List<String> protocols) { this.allowedTlsProtocols = protocols; this }
        Builder allowedCipherSuites(List<String> suites) { this.allowedCipherSuites = suites; this }
        Builder retryPolicy(RetryPolicy policy) { this.retryPolicy = policy; this }
        /** Verified TLS against this context's trust material (private CA, test trust store). */
        Builder sslContext(SSLContext ctx) { this.sslContext = ctx; this }
        Builder maxRedirects(int hops) { this.maxRedirects = hops; this }

        GroovyHttpClient build() {
            def cfg = new SecurityConfig([
                    baseUrl                    : baseUrl,
                    connectTimeout             : connectTimeout,
                    requestTimeout             : requestTimeout,
                    insecureAllowed            : insecureAllowed,
                    allowAbsoluteUrls          : allowAbsoluteUrls,
                    maxResponseBytes           : maxResponseBytes,
                    cookiePolicy               : cookiePolicy,
                    allowedHosts               : allowedHosts ?: [URI.create(baseUrl).host] as Set,
                    enableLogging              : enableLogging,
                    enableMetrics              : enableMetrics,
                    allowRedirects             : allowRedirects,
                    failureThreshold           : failureThreshold,
                    resetTimeoutMs             : resetTimeoutMs,
                    enforceHostnameVerification: enforceHostnameVerification,
                    allowedTlsProtocols        : allowedTlsProtocols,
                    allowedCipherSuites        : allowedCipherSuites,
                    retryPolicy                : retryPolicy,
                    sslContext                 : sslContext,
                    maxRedirects               : maxRedirects
            ])
            new GroovyHttpClient(cfg)
        }
    }

    static Builder builder(String baseUrl) { new Builder(baseUrl) }

    /* =========================================================================
     * Construction
     * ========================================================================= */

    /**
     * Client with {@link SecurityConfig#defaults(String)}: certificates and host names verified.
     * (Before 2.0.0 this used the trust-all testing profile.)
     */
    GroovyHttpClient(String baseUrl) { this(SecurityConfig.defaults(baseUrl)) }

    GroovyHttpClient(SecurityConfig config) {
        if (config == null) throw new IllegalArgumentException("SecurityConfig is required")

        this.config = config
        this.host = URI.create(config.baseUrl)

        this.cookieManager = new CookieManager(null, config.cookiePolicy ?: CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        this.circuitBreaker = new CircuitBreaker(
                config.failureThreshold ?: DEFAULT_FAILURE_THRESHOLD,
                config.resetTimeoutMs   ?: DEFAULT_RESET_TIMEOUT_MS
        )

        this.allowAbsoluteUrls = !!config.allowAbsoluteUrls
        Set<String> hosts = new LinkedHashSet<String>(config.allowedHosts ?: [] as Set<String>)
        hosts.add(SecurityConfig.normalizeHost(this.host.host))
        this.allowedHosts = Collections.unmodifiableSet(hosts)

        this.maxResponseBytes = config.maxResponseBytes ?: 10_000_000L

        ThreadFactory tf = Thread.ofVirtual()
                .name("http-client-", THREAD_COUNTER.getAndIncrement())
                .factory()

        this.executor = Executors.newThreadPerTaskExecutor(tf)

        def b = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(config.connectTimeout ?: DEFAULT_CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_2)
                // Redirects are followed by sendFollowingRedirects so every hop is checked against
                // allowedHosts (SEC-3); the JDK client must never follow them itself.
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookieManager)
                .proxy(ProxySelector.getDefault())

        if (config.insecureAllowed) {
            this.httpClient = createInsecureClient(b, config)
            log.warn("[GroovyHttpClient] Insecure TLS permitted by SecurityConfig (trustAll/testing). DO NOT use in production.")
        } else {
            this.httpClient = createSecureClient(b, config)
        }
    }

    void addInterceptor(Closure interceptor) {
        if (!interceptor) return
        interceptors << interceptor
    }

    /* =========================================================================
     * TLS
     * ========================================================================= */

    private static HttpClient createSecureClient(HttpClient.Builder baseBuilder, SecurityConfig config) {
        SSLParameters sslParams = new SSLParameters()

        if (config.allowedTlsProtocols) sslParams.setProtocols(config.allowedTlsProtocols as String[])
        if (config.allowedCipherSuites) sslParams.setCipherSuites(config.allowedCipherSuites as String[])

        sslParams.setEndpointIdentificationAlgorithm(config.enforceHostnameVerification ? "HTTPS" : null)

        baseBuilder.sslParameters(sslParams)
        baseBuilder.sslContext(config.sslContext ?: SSLContext.getDefault())
        baseBuilder.build()
    }

    private static HttpClient createInsecureClient(HttpClient.Builder baseBuilder, SecurityConfig config) {
        TrustManager[] trustAll = [
                new X509TrustManager() {
                    X509Certificate[] getAcceptedIssuers() { null }
                    void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        ] as TrustManager[]

        SSLContext sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, new SecureRandom())

        SSLParameters sslParams = new SSLParameters()
        sslParams.setEndpointIdentificationAlgorithm(config.enforceHostnameVerification ? "HTTPS" : null)
        sslParams.setApplicationProtocols(["h2", "http/1.1"] as String[])

        if (config.allowedTlsProtocols) sslParams.setProtocols(config.allowedTlsProtocols as String[])
        if (config.allowedCipherSuites) sslParams.setCipherSuites(config.allowedCipherSuites as String[])

        baseBuilder.sslContext(sslContext).sslParameters(sslParams).build()
    }

    /* =========================================================================
     * Cookie facade used by tests
     * ========================================================================= */

    static class SimpleCookieHandler {
        private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>()

        void addCookie(String name, String value) { Cookies.validate(name, value); cookies[name] = value }
        void removeCookie(String name) { cookies.remove(name) }
        void clear() { cookies.clear() }

        Map<String, String> getCookies() { Collections.unmodifiableMap(cookies) }

        String asHeaderValue() { cookies.collect { k, v -> "${k}=${v}" }.join("; ") }

        boolean isEmpty() { cookies.isEmpty() }
    }

    /* =========================================================================
     * Request DSL wrapper
     * ========================================================================= */

    private class GroovyRequestBuilder {
        private final HttpRequest.Builder builder
        private final Map<String, List<String>> headers = new ConcurrentHashMap<String, List<String>>()
        private final Map<String, List<String>> queryParams = new ConcurrentHashMap<String, List<String>>()
        private RetryPolicy retryOverride
        // C-1: per-request settings recorded here and replayed onto every (re)built request
        private Duration timeoutOverride
        private HttpClient.Version versionOverride
        private boolean expectContinueOverride = false

        private HttpRequest.BodyPublisher bodyPublisher = null
        private String bodyContentType = null

        GroovyRequestBuilder(HttpRequest.Builder builder) { this.builder = builder }

        void header(String name, String value) {
            builder.header(name, value)
            headers.computeIfAbsent(name) { new CopyOnWriteArrayList<String>() } << value
        }

        boolean hasHeader(String name) {
            headers.keySet().any { it.equalsIgnoreCase(name) }
        }

        /** Per-request timeout; overrides SecurityConfig.requestTimeout for this request only. */
        GroovyRequestBuilder timeout(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("timeout must be a positive duration")
            }
            timeoutOverride = duration
            this
        }

        /** Per-request HTTP version preference. */
        GroovyRequestBuilder version(HttpClient.Version v) { versionOverride = v; this }

        /** Send 'Expect: 100-continue' for this request. */
        GroovyRequestBuilder expectContinue(boolean enable) { expectContinueOverride = enable; this }

        /** Replace any value of this header set so far (default headers included). */
        void setHeader(String name, String value) {
            headers.keySet().removeIf { String k -> k.equalsIgnoreCase(name) }
            header(name, value)
        }

        void cookie(String name, String value) { header("Cookie", Cookies.pair(name, value)) }

        void cookies(Map<String, String> cookies) {
            header("Cookie", cookies.collect { k, v -> Cookies.pair(k, v) }.join("; "))
        }

        /** query a:1, b:'two' */
        void query(Map<String, ?> params) {
            params?.each { k, v ->
                if (v == null) return
                def key = k.toString()
                if (v instanceof Iterable) {
                    v.each { vv ->
                        if (vv != null) {
                            this.@queryParams.computeIfAbsent(key) { new CopyOnWriteArrayList<String>() } << vv.toString()
                        }
                    }
                } else {
                    this.@queryParams.computeIfAbsent(key) { new CopyOnWriteArrayList<String>() } << v.toString()
                }
            }
        }

        /** application/x-www-form-urlencoded */
        void form(Map<String, ?> params, Charset charset = Charset.forName("UTF-8")) {
            def encoded = encodeForm(params ?: [:], charset)
            bodyPublisher = HttpRequest.BodyPublishers.ofString(encoded, charset)
            bodyContentType = "application/x-www-form-urlencoded; charset=${charset.name()}"
        }

        /** JSON encode + set Content-Type */
        void json(Object obj, Charset charset = Charset.forName("UTF-8")) {
            def jsonText = (obj instanceof CharSequence) ? obj.toString() : groovy.json.JsonOutput.toJson(obj)
            bodyPublisher = HttpRequest.BodyPublishers.ofString(jsonText, charset)
            bodyContentType = "application/json; charset=${charset.name()}"
        }

        /** Override retry policy per request */
        void retry(@DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = RetryPolicy.Config) Closure c) {
            def b = new RetryPolicy.Config()
            c.delegate = b
            c.resolveStrategy = Closure.DELEGATE_ONLY
            c.call()
            retryOverride = b.build()
        }

        RetryPolicy getRetryPolicyOrNull() { retryOverride }

        Map<String, List<String>> getQueryParams() {
            if (this.@queryParams.isEmpty()) {
                return [:]
            }
            def result = [:]
            this.@queryParams.each { k, v ->
                result[k] = new ArrayList<String>(v)
            }
            result
        }

        HttpRequest.BodyPublisher getBodyPublisherOrNull() { bodyPublisher }
        String getBodyContentTypeOrNull() { bodyContentType }

        Duration getTimeoutOrNull() { timeoutOverride }
        HttpClient.Version getVersionOrNull() { versionOverride }

        /**
         * Apply what the DSL recorded to a fresh request builder. Every attempt (retries, and the
         * initial send) is built through here, so nothing set in the DSL is lost (C-1). Before 2.0.0
         * a methodMissing forwarded unknown calls to a builder that was never sent; unknown DSL calls
         * now fail with MissingMethodException instead of being silently dropped.
         */
        HttpRequest.Builder applyTo(HttpRequest.Builder b, Duration defaultTimeout) {
            b.timeout(timeoutOverride ?: defaultTimeout)
            if (versionOverride != null) b.version(versionOverride)
            if (expectContinueOverride) b.expectContinue(true)
            getHeaders().each { k, vs -> vs.each { v -> b.header(k, v) } }
            b
        }

        HttpRequest.Builder unwrap() { builder }

        Map<String, List<String>> getHeaders() {
            new HashMap<String, List<String>>(headers).collectEntries { k, v -> [(k): new ArrayList<String>(v)] }
        }
    }

    /* =========================================================================
     * Response wrapper (bytes-first, content-type guarded parsing)
     * ========================================================================= */

    static class HttpClientResponse {
        final byte[] bytes
        final String body
        final Map<String, List<String>> headers
        final int statusCode
        final String contentType
        final Charset charset

        HttpClientResponse(HttpResponse<byte[]> response) {
            this.bytes = response.body() ?: new byte[0]
            this.headers = response.headers().map()
            this.statusCode = response.statusCode()
            this.contentType = getHeader("Content-Type")
            this.charset = parseCharset(contentType) ?: Charset.forName("UTF-8")
            this.body = new String(bytes, charset)
        }

        String getHeader(String name) {
            def entry = headers.find { k, v -> k.equalsIgnoreCase(name) }
            entry?.value?.getAt(0)
        }

        List<String> getHeaders(String name) {
            headers.findAll { k, v -> k.equalsIgnoreCase(name) }.values().flatten() as List<String>
        }

        boolean hasHeader(String name) { headers.any { k, v -> k.equalsIgnoreCase(name) } }

        boolean isSuccess() { statusCode >= 200 && statusCode <= 299 }
        boolean isClientError() { statusCode >= 400 && statusCode <= 499 }
        boolean isServerError() { statusCode >= 500 && statusCode <= 599 }

        private volatile Object jsonCache
        private volatile boolean jsonTried = false

        private volatile Object xmlCache
        private volatile boolean xmlTried = false

        Object getJson() { requireJson() }
        Object getXml() { requireXml() }

        Object getJsonOrNull() {
            if (!jsonTried) {
                jsonTried = true
                jsonCache = canParseJson() ? new JsonSlurper().parseText(body) : null
            }
            jsonCache
        }

        Object getXmlOrNull() {
            if (!xmlTried) {
                xmlTried = true
                xmlCache = canParseXml() ? new XmlSlurper().parseText(body) : null
            }
            xmlCache
        }

        Object requireJson() {
            def v = getJsonOrNull()
            if (v == null) throw new ContentParseException("Response is not JSON (Content-Type=${contentType})")
            v
        }

        Object requireXml() {
            def v = getXmlOrNull()
            if (v == null) throw new ContentParseException("Response is not XML (Content-Type=${contentType})")
            v
        }

        private boolean canParseJson() {
            if (!contentType) return false
            def ct = contentType.toLowerCase()
            ct.contains("application/json") || ct.contains("+json")
        }

        private boolean canParseXml() {
            if (!contentType) return false
            def ct = contentType.toLowerCase()
            ct.contains("application/xml") || ct.contains("text/xml") || ct.contains("+xml")
        }

        private static Charset parseCharset(String contentType) {
            if (!contentType) return null
            def m = (contentType =~ /(?i)\bcharset\s*=\s*["']?([^;"'\s]+)["']?/)
            if (m.find()) {
                try { return Charset.forName(m.group(1)) } catch (ignored) { return null }
            }
            null
        }

        String toString() { body }
    }

    /* =========================================================================
     * CookieManager helpers
     * ========================================================================= */

    List<HttpCookie> getCookies() { cookieManager.cookieStore.get(host).collect() }

    HttpCookie getCookie(String name) { cookieManager.cookieStore.get(host).find { it.name == name } }

    GroovyHttpClient addCookie(String name, String value, String path = "/") {
        def cookie = new HttpCookie(name, value)
        cookie.path = path
        cookie.domain = host.host
        cookieManager.cookieStore.add(host, cookie)
        this
    }

    GroovyHttpClient addCookie(HttpCookie cookie) { cookieManager.cookieStore.add(host, cookie); this }

    GroovyHttpClient removeCookie(String name) {
        cookieManager.cookieStore.get(host).findAll { it.name == name }.each { c ->
            cookieManager.cookieStore.remove(host, c)
        }
        this
    }

    GroovyHttpClient clearCookies() { cookieManager.cookieStore.removeAll(); this }

    GroovyHttpClient setCookiePolicy(CookiePolicy policy) { cookieManager.cookiePolicy = policy; this }

    CookiePolicy getCookiePolicy() { cookieManager.cookiePolicy }
    CookieStore getCookieStore() { cookieManager.cookieStore }

    /* =========================================================================
     * Default headers
     * ========================================================================= */

    GroovyHttpClient withHeader(String name, String value) {
        defaultHeaders.compute(name) { _, v ->
            def list = (v ?: new java.util.concurrent.CopyOnWriteArrayList<String>())
            list << value
            list
        }
        this
    }

    GroovyHttpClient setHeader(String name, String value) {
        defaultHeaders[name] = new java.util.concurrent.CopyOnWriteArrayList<String>([value])
        this
    }

    GroovyHttpClient withHeaders(Map<String, String> headers) {
        headers?.each { k, v -> withHeader(k, v) }
        this
    }

    GroovyHttpClient setHeaders(Map<String, String> headers) {
        defaultHeaders.clear()
        headers?.each { k, v -> setHeader(k, v) }
        this
    }

    GroovyHttpClient clearHeaders() { defaultHeaders.clear(); this }

    Map<String, List<String>> getDefaultHeaders() {
        defaultHeaders.collectEntries { k, v -> [(k): (v?.toList() ?: [])] }
    }

    /* =========================================================================
     * Public verbs
     * ========================================================================= */

    CompletableFuture<HttpClientResponse> get(String path,
                                              @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("GET", path, null, cfg)
    }

    CompletableFuture<HttpClientResponse> delete(String path,
                                                 @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("DELETE", path, null, cfg)
    }

    CompletableFuture<HttpClientResponse> head(String path,
                                               @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("HEAD", path, null, cfg)
    }

    CompletableFuture<HttpClientResponse> options(String path,
                                                  @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("OPTIONS", path, null, cfg)
    }

    CompletableFuture<HttpClientResponse> post(String path, String body,
                                               @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("POST", path, body, cfg)
    }

    CompletableFuture<HttpClientResponse> put(String path, String body,
                                              @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("PUT", path, body, cfg)
    }

    CompletableFuture<HttpClientResponse> patch(String path, String body,
                                                @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        send("PATCH", path, body, cfg)
    }

    // Sync
    HttpClientResponse getSync(String path, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        get(path, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse postSync(String path, String body, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        post(path, body, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse putSync(String path, String body, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        put(path, body, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse patchSync(String path, String body, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        patch(path, body, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse deleteSync(String path, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        delete(path, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse headSync(String path, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        head(path, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
    HttpClientResponse optionsSync(String path, Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        options(path, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /* =========================================================================
     * Core send (retry + interceptor + circuit breaker)
     * ========================================================================= */

    private CompletableFuture<HttpClientResponse> send(
            String method,
            String path,
            String body = null,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null
    ) {
        def baseUri = resolveUri(path)

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(baseUri)
                .timeout(config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)

        def wrapped = new GroovyRequestBuilder(rb)

        // Default headers
        defaultHeaders.each { name, values ->
            values?.each { v -> wrapped.header(name, v) }
        }

        // DSL
        if (cfg) {
            cfg.delegate = wrapped
            cfg.resolveStrategy = Closure.DELEGATE_FIRST
            if (cfg.maximumNumberOfParameters == 1) {
                cfg.call(wrapped)
            } else {
                cfg.call()
            }
        }

        // cookieHandler cookies if no explicit Cookie header set
        if (!wrapped.hasHeader("Cookie") && !cookieHandler.isEmpty()) {
            wrapped.header("Cookie", cookieHandler.asHeaderValue())
        }

        // Apply query params (after DSL)
        def finalUri = appendQueryParams(baseUri, wrapped.getQueryParams())
        rb.uri(finalUri)

        // Decide body publisher
        HttpRequest.BodyPublisher pub = wrapped.getBodyPublisherOrNull()
        String bodyCt = wrapped.getBodyContentTypeOrNull()

        if (bodyCt && !wrapped.hasHeader("Content-Type")) {
            wrapped.header("Content-Type", bodyCt)
        }

        if (pub == null) {
            // fall back to provided body string (if any)
            if (body != null && ["POST","PUT","PATCH"].contains(method.toUpperCase())) {
                pub = HttpRequest.BodyPublishers.ofString(body)
            }
        }

        def requestSupplier = { ->
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(finalUri)
            // C-1: replay the per-request DSL settings (timeout, version, expectContinue, headers)
            wrapped.applyTo(b, config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)

            switch (method.toUpperCase()) {
                case "GET":     b.GET(); break
                case "DELETE":  b.DELETE(); break
                case "HEAD":    b.method("HEAD", HttpRequest.BodyPublishers.noBody()); break
                case "OPTIONS": b.method("OPTIONS", HttpRequest.BodyPublishers.noBody()); break
                case "POST":
                case "PUT":
                case "PATCH":
                    def p = pub ?: HttpRequest.BodyPublishers.noBody()
                    if (method.toUpperCase() == "PATCH") b.method("PATCH", p)
                    else if (method.toUpperCase() == "POST") b.POST(p)
                    else b.PUT(p)
                    break
                default:
                    b.method(method.toUpperCase(), pub ?: HttpRequest.BodyPublishers.noBody())
            }
            b.build()
        }

        def effectiveRetry = wrapped.getRetryPolicyOrNull() ?: config.retryPolicy ?: RetryPolicy.none()
        def ctx = new RequestContext(method.toUpperCase(), finalUri, requestSupplier, effectiveRetry)

        def chain = buildInterceptorChain()

        // Circuit breaker gates the overall operation
        return executeWithCircuitBreaker {
            attemptWithRetry(ctx, chain).thenApply { HttpClientResponse r -> raiseForStatus(r) }
        }
    }

    private Closure<CompletableFuture<HttpClientResponse>> buildInterceptorChain() {
        Closure<CompletableFuture<HttpClientResponse>> terminal = { RequestContext ctx ->
            HttpRequest req = ctx.requestSupplier.call()
            sendFollowingRedirects(req, 0)
                    .thenApply { HttpResponse<byte[]> resp ->
                        // byte-accurate maxResponseBytes
                        if (maxResponseBytes != Long.MAX_VALUE && resp.body() != null && resp.body().length > maxResponseBytes) {
                            throw new HttpResponseException(413, "Body too large (>${maxResponseBytes} bytes)")
                        }

                        // >= 400 is raised AFTER retry (raiseForStatus), so status-based retry can see the
                        // response (SEC-4). Interceptors now receive 4xx/5xx responses rather than exceptions.
                        new HttpClientResponse(resp)
                    }
        }

        // fold interceptors from last to first
        Closure<CompletableFuture<HttpClientResponse>> chain = terminal
        interceptors.reverseEach { Closure interceptor ->
            def next = chain
            chain = { RequestContext ctx ->
                try {
                    if (interceptor.maximumNumberOfParameters == 2) {
                        return (CompletableFuture<HttpClientResponse>) interceptor.call(ctx, next)
                    }
                    // allow { ctx -> ... } form too (must call ctx.proceed())
                    return (CompletableFuture<HttpClientResponse>) interceptor.call(ctx)
                } catch (Throwable t) {
                    def f = new CompletableFuture<HttpClientResponse>()
                    f.completeExceptionally(t)
                    return f
                }
            }
        }
        chain
    }

    private CompletableFuture<HttpClientResponse> attemptWithRetry(
            RequestContext ctx,
            Closure<CompletableFuture<HttpClientResponse>> chain
    ) {
        def attempt = new AtomicInteger(1)
        def promise = new CompletableFuture<HttpClientResponse>()
        RetryPolicy policy = ctx.retryPolicy
        // SEC-10: POST / PATCH are retried only when the policy opts in with retryNonIdempotent
        boolean retryable = policy != null && policy.maxAttempts > 1 && policy.allowsMethod(ctx.method)

        Closure doAttempt
        doAttempt = { ->
            ctx.attempt = attempt.get()
            chain.call(ctx).whenComplete { HttpClientResponse resp, Throwable err ->
                Throwable cause = (err == null) ? null : unwrapCompletion(err)
                // SEC-4: the chain now returns 4xx/5xx responses, so status-code retry can actually fire
                boolean again = retryable && attempt.get() < policy.maxAttempts &&
                        (cause == null ? policy.shouldRetry(null, resp) : policy.shouldRetry(cause, null))
                if (again) {
                    long delayMs = policy.nextDelayMs(attempt.get())
                    attempt.incrementAndGet()
                    CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor as java.util.concurrent.Executor)
                            .execute { doAttempt.call() }
                    return
                }
                if (cause == null) promise.complete(resp)
                else promise.completeExceptionally(cause)
            }
        }

        doAttempt.call()
        promise
    }

    private static HttpClientResponse raiseForStatus(HttpClientResponse r) {
        if (r.statusCode >= 400) throw new HttpResponseException(r.statusCode, r.body)
        r
    }

    private static Throwable unwrapCompletion(Throwable t) {
        Throwable cause = t
        while (cause != null) {
            if (cause instanceof CompletionException && cause.cause != null) { cause = cause.cause; continue }
            // The JDK client wraps a BodySubscriber failure in IOException. Our own exceptions (e.g. the
            // 413 from BoundedBodySubscriber) must surface as themselves, not as a retryable I/O error.
            if (cause instanceof IOException && (cause.cause instanceof HttpResponseException ||
                    cause.cause instanceof RedirectRejectedException)) { cause = cause.cause; continue }
            break
        }
        cause
    }

    /* =========================================================================
     * URI resolution (SSRF guard) + query encoding helpers
     * ========================================================================= */

    private URI resolveUri(String path) {
        if (!path) return host

        def lower = path.toLowerCase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (!allowAbsoluteUrls) throw new IllegalArgumentException("Absolute URLs disabled by configuration")
            def uri = new URI(path)
            def h = normalizeHost(uri.host)
            if (!allowedHosts.isEmpty() && h && !allowedHosts.contains(h)) {
                throw new IllegalArgumentException("Target host not allowed: ${uri.host}")
            }
            return uri
        }

        String base = host.toString().endsWith('/') ? host.toString()[0..-2] : host.toString()
        String p = path.startsWith('/') ? path : "/$path"
        URI.create(base + p)
    }

    private static String normalizeHost(String h) { SecurityConfig.normalizeHost(h) }

    /* =========================================================================
     * Redirects (SEC-3 / SEC-12): followed here, never by the JDK client, so every
     * hop is policy-checked and credentials do not cross origins
     * ========================================================================= */

    private static final Set<Integer> REDIRECT_CODES = [301, 302, 303, 307, 308] as Set<Integer>
    private static final Set<String> CREDENTIAL_HEADERS = ['authorization', 'proxy-authorization', 'cookie'] as Set<String>

    private CompletableFuture<HttpResponse<byte[]>> sendFollowingRedirects(HttpRequest req, int hop) {
        httpClient.sendAsync(req, boundedBodyHandler()).thenCompose { HttpResponse<byte[]> resp ->
            if (!config.allowRedirects || !REDIRECT_CODES.contains(resp.statusCode())) {
                return CompletableFuture.completedFuture(resp)
            }
            Optional<String> location = resp.headers().firstValue('Location')
            if (!location.isPresent()) {
                return CompletableFuture.completedFuture(resp)
            }
            if (hop >= config.maxRedirects) {
                throw new RedirectRejectedException("Too many redirects (more than ${config.maxRedirects}) from ${req.uri()}")
            }
            URI target = req.uri().resolve(location.get().trim())
            checkRedirectTarget(req.uri(), target)
            return sendFollowingRedirects(redirectRequest(req, target, resp.statusCode()), hop + 1)
        } as CompletableFuture<HttpResponse<byte[]>>
    }

    private void checkRedirectTarget(URI from, URI to) {
        String scheme = to.scheme?.toLowerCase(Locale.ROOT)
        if (!(scheme in ['http', 'https'])) {
            throw new RedirectRejectedException("Redirect to unsupported scheme refused: ${to}")
        }
        if ('https'.equalsIgnoreCase(from.scheme) && scheme == 'http') {
            throw new RedirectRejectedException("Redirect from HTTPS to HTTP refused: ${to}")
        }
        String h = normalizeHost(to.host)
        if (!h || !allowedHosts.contains(h)) {
            throw new RedirectRejectedException("Redirect target host not allowed: ${to.host}")
        }
    }

    private static int effectivePort(URI u) {
        u.port != -1 ? u.port : ('https'.equalsIgnoreCase(u.scheme) ? 443 : 80)
    }

    private static boolean sameOrigin(URI a, URI b) {
        a.scheme?.equalsIgnoreCase(b.scheme) &&
                normalizeHost(a.host) == normalizeHost(b.host) &&
                effectivePort(a) == effectivePort(b)
    }

    /** 303 (and 301/302 after POST) become GET without a body, as browsers and the JDK do; 307/308 keep method and body. */
    private static HttpRequest redirectRequest(HttpRequest prev, URI target, int status) {
        String method = prev.method()
        boolean toGet = (status == 303 && method != 'HEAD') || ((status == 301 || status == 302) && method == 'POST')
        boolean crossOrigin = !sameOrigin(prev.uri(), target)
        HttpRequest.Builder b = HttpRequest.newBuilder(prev, { String name, String value ->
            String n = name.toLowerCase(Locale.ROOT)
            if (crossOrigin && CREDENTIAL_HEADERS.contains(n)) return false
            if (toGet && (n == 'content-type' || n == 'content-length')) return false
            return true
        } as java.util.function.BiPredicate<String, String>).uri(target)
        if (toGet) b.GET()
        b.build()
    }

    /* =========================================================================
     * Bounded response bodies (SEC-5): stop reading once maxResponseBytes is crossed
     * ========================================================================= */

    private HttpResponse.BodyHandler<byte[]> boundedBodyHandler() {
        long max = maxResponseBytes
        return { HttpResponse.ResponseInfo info -> new BoundedBodySubscriber(max) } as HttpResponse.BodyHandler<byte[]>
    }

    @CompileStatic
    static class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long max
        private final CompletableFuture<byte[]> result = new CompletableFuture<byte[]>()
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        private Flow.Subscription subscription
        private long received = 0L

        BoundedBodySubscriber(long max) { this.max = max }

        @Override
        CompletionStage<byte[]> getBody() { result }

        @Override
        void onSubscribe(Flow.Subscription s) {
            this.subscription = s
            s.request(Long.MAX_VALUE)
        }

        @Override
        void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return
            for (ByteBuffer bb : items) {
                int n = bb.remaining()
                received += n
                if (max != Long.MAX_VALUE && received > max) {
                    subscription.cancel()
                    result.completeExceptionally(new HttpResponseException(413, 'Response body exceeds maxResponseBytes (' + max + ')'))
                    return
                }
                byte[] chunk = new byte[n]
                bb.get(chunk)
                buffer.write(chunk, 0, n)
            }
        }

        @Override
        void onError(Throwable t) { result.completeExceptionally(t) }

        @Override
        void onComplete() { result.complete(buffer.toByteArray()) }
    }

    private static URI appendQueryParams(URI uri, Map<String, List<String>> params) {
        if (!params || params.isEmpty()) return uri

        def existing = uri.rawQuery
        def extra = params.collectMany { k, vs ->
            vs.collect { v ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        }.join("&")

        def newQuery = existing ? (existing + "&" + extra) : extra

        new URI(
                uri.scheme,
                uri.authority,
                uri.path,
                newQuery,
                uri.fragment
        )
    }

    private static String encodeForm(Map<String, ?> params, Charset charset) {
        params.collectMany { k, v ->
            if (v == null) return []
            if (v instanceof Iterable) {
                return v.findAll { it != null }.collect { vv ->
                    "${URLEncoder.encode(k.toString(), charset.name())}=${URLEncoder.encode(vv.toString(), charset.name())}"
                }
            }
            ["${URLEncoder.encode(k.toString(), charset.name())}=${URLEncoder.encode(v.toString(), charset.name())}"]
        }.join("&")
    }

    /* =========================================================================
     * Circuit breaker wrapper (real HALF_OPEN probe + single probe in-flight)
     * ========================================================================= */

    private <T> CompletableFuture<T> executeWithCircuitBreaker(Supplier<CompletableFuture<T>> supplier) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit breaker is {} — rejecting request", circuitBreaker.state)
            def f = new CompletableFuture<T>()
            f.completeExceptionally(new CircuitOpenException("Circuit is ${circuitBreaker.state}"))
            return f
        }

        CompletableFuture<T> fut
        try {
            fut = supplier.get()
        } catch (Throwable t) {
            circuitBreaker.onError(t)
            def f = new CompletableFuture<T>()
            f.completeExceptionally(t)
            return f
        }

        fut.whenComplete { res, err ->
            if (err == null) {
                circuitBreaker.onSuccess()
                return
            }
            circuitBreaker.onError(unwrapCompletion(err))
        }
        fut
    }

    private static boolean isTransient(Throwable t) {
        if (t == null) return false
        if (t instanceof java.io.IOException) return true
        if (t instanceof java.net.http.HttpTimeoutException) return true
        // completion wrapper is removed by unwrapCompletion
        false
    }

    /* =========================================================================
     * Multipart + downloadBytes (fix CRLF + random boundary)
     * ========================================================================= */

    private static HttpRequest.BodyPublisher buildMultipartBody(List<MultipartPart> parts, String boundary) {
        def byteArrays = []
        parts.each { MultipartPart part ->
            def header =
                    "--${boundary}\r\n" +
                            "Content-Disposition: form-data; name=\"${MultipartPart.quote(part.name)}\"; filename=\"${MultipartPart.quote(part.filename)}\"\r\n" +
                            "Content-Type: ${part.contentType}\r\n" +
                            "\r\n"
            byteArrays << header.getBytes("UTF-8")
            byteArrays << part.data
            byteArrays << "\r\n".getBytes("UTF-8")
        }
        byteArrays << "--${boundary}--\r\n".getBytes("UTF-8")
        HttpRequest.BodyPublishers.ofByteArrays(byteArrays)
    }

    static class MultipartDSLBuilder {
        List<MultipartPart> parts = []
        Map<String, List<String>> headers = [:].withDefault { [] }

        void part(@DelegatesTo(MultipartPart.Builder) Closure c) {
            def pb = new MultipartPart.Builder()
            c.delegate = pb
            c.resolveStrategy = Closure.DELEGATE_ONLY
            c()
            parts << pb.build()
        }

        void header(String name, String value) { headers[name] << value }
    }

    CompletableFuture<HttpClientResponse> postMultipart(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = MultipartDSLBuilder) Closure dsl
    ) {
        def mb = new MultipartDSLBuilder()
        if (dsl.maximumNumberOfParameters == 1) dsl.call(mb)
        else {
            dsl.delegate = mb
            dsl.resolveStrategy = Closure.DELEGATE_ONLY
            dsl.call()
        }

        postMultipart(path, mb.parts) { rb ->
            mb.headers.each { k, vs -> vs.each { v -> rb.header(k, v) } }
        }
    }

    HttpClientResponse postMultipartSync(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = MultipartDSLBuilder) Closure dsl,
            Duration timeout = DEFAULT_SYNC_TIMEOUT
    ) {
        postMultipart(path, dsl).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    CompletableFuture<HttpClientResponse> postMultipart(
            String path,
            List<MultipartPart> parts,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null
    ) {
        if (!parts) throw new IllegalArgumentException("Multipart parts are required for postMultipart")

        def uri = resolveUri(path)
        def boundary = "----GroovyBoundary${UUID.randomUUID()}"

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)

        def wrapped = new GroovyRequestBuilder(rb)
        defaultHeaders.each { n, vs -> vs?.each { wrapped.header(n, it) } }

        if (cfg) {
            cfg.delegate = wrapped
            cfg.resolveStrategy = Closure.DELEGATE_FIRST
            if (cfg.maximumNumberOfParameters == 1) {
                cfg.call(wrapped)
            } else {
                cfg.call()
            }
        }

        if (!wrapped.hasHeader("Cookie") && !cookieHandler.isEmpty()) {
            wrapped.header("Cookie", cookieHandler.asHeaderValue())
        }

        wrapped.header("Content-Type", "multipart/form-data; boundary=${boundary}")

        def pub = buildMultipartBody(parts, boundary)

        def reqSupplier = { ->
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri)
            wrapped.applyTo(b, config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)
            b.POST(pub).build()
        }

        def effectiveRetry = wrapped.getRetryPolicyOrNull() ?: config.retryPolicy ?: RetryPolicy.none()
        def ctx = new RequestContext("POST", uri, reqSupplier, effectiveRetry)

        def chain = buildInterceptorChain()

        executeWithCircuitBreaker {
            // >= 400 raised after retry; the breaker counts only transient errors and 5xx
            attemptWithRetry(ctx, chain).thenApply { HttpClientResponse r -> raiseForStatus(r) }
        }
    }

    HttpClientResponse postMultipartSync(
            String path,
            List<MultipartPart> parts,
            Closure cfg = null,
            Duration timeout = DEFAULT_SYNC_TIMEOUT
    ) {
        postMultipart(path, parts, cfg).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    CompletableFuture<byte[]> downloadBytes(String path,
                                            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {

        // Reuse the normal request pipeline (cookies, interceptors, circuit breaker, retries)
        return get(path, cfg).thenApply { HttpClientResponse resp ->
            resp.bytes
        }
    }

    byte[] downloadBytesSync(String path, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        downloadBytes(path).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /* =========================================================================
     * Lifecycle
     * ========================================================================= */

    @Override
    void close() {
        // SEC-9: close the JDK client too (selector thread, pooled connections), with a bounded wait
        try {
            httpClient.shutdown()
            if (!httpClient.awaitTermination(Duration.ofSeconds(5))) httpClient.shutdownNow()
        } catch (InterruptedException ie) {
            httpClient.shutdownNow()
            Thread.currentThread().interrupt()
        } catch (ignored) { }
        try { executor?.close() } catch (ignored) { }
    }

    String toString(path = '/') { resolveUri(path).toString() }

    /* =========================================================================
     * Retry policy
     * ========================================================================= */

    @Immutable
    static class RetryPolicy {
        int maxAttempts
        long initialDelayMs
        long maxDelayMs
        boolean jitter
        boolean retryOnIOException
        boolean retryOnTimeout
        Set<Integer> retryStatusCodes

        /** SEC-10: retry non-idempotent methods (POST, PATCH, CONNECT) too. Off by default. */
        boolean retryNonIdempotent

        private static final Set<String> IDEMPOTENT = ['GET', 'HEAD', 'OPTIONS', 'PUT', 'DELETE', 'TRACE'] as Set<String>

        static RetryPolicy none() {
            new RetryPolicy(1, 0, 0, false, false, false, Collections.emptySet() as Set<Integer>, false)
        }

        boolean allowsMethod(String method) {
            retryNonIdempotent || IDEMPOTENT.contains(method?.toUpperCase(Locale.ROOT))
        }

        static class Config  {
            int maxAttempts = 1
            long initialDelayMs = 200
            long maxDelayMs = 2_000
            boolean jitter = true
            boolean retryOnIOException = true
            boolean retryOnTimeout = true
            Set<Integer> retryStatusCodes = ([500,502,503,504] as Set<Integer>)
            boolean retryNonIdempotent = false

            /** Also retry POST / PATCH. Only safe when the server de-duplicates (e.g. an Idempotency-Key). */
            void retryNonIdempotent(boolean enabled = true) { this.retryNonIdempotent = enabled }

            void maxAttempts(int n) { this.maxAttempts = Math.max(1, n) }
            void backoffExponential(long initialMs = 200, long maxMs = 2_000, boolean jitter = true) {
                this.initialDelayMs = Math.max(0, initialMs)
                this.maxDelayMs = Math.max(this.initialDelayMs, maxMs)
                this.jitter = jitter
            }
            void onIoExceptions(boolean enabled = true) { this.retryOnIOException = enabled }
            void onTimeouts(boolean enabled = true) { this.retryOnTimeout = enabled }
            void onStatusCodes(Collection<Integer> codes) { this.retryStatusCodes = (codes ?: []) as Set<Integer> }
            void on5xx(boolean enabled = true) {
                this.retryStatusCodes = enabled ? ((500..599).toList() as Set<Integer>) : (Collections.emptySet() as Set<Integer>)
            }

            RetryPolicy build() {
                new RetryPolicy(maxAttempts, initialDelayMs, maxDelayMs, jitter, retryOnIOException, retryOnTimeout,
                        retryStatusCodes ?: (Collections.emptySet() as Set<Integer>), retryNonIdempotent)
            }
        }

        boolean shouldRetry(Throwable error, HttpClientResponse resp) {
            if (resp != null) {
                return retryStatusCodes?.contains(resp.statusCode)
            }
            if (error == null) return false
            if (retryOnIOException && error instanceof IOException) return true
            if (retryOnTimeout && error instanceof java.net.http.HttpTimeoutException) return true
            false
        }

        long nextDelayMs(int attemptNumber) {
            if (attemptNumber <= 0) attemptNumber = 1
            // exponential: initial * 2^(attempt-1)
            double raw = initialDelayMs * Math.pow(2d, (double)(attemptNumber - 1))
            long d = (long) Math.min(maxDelayMs, Math.max(0, raw))
            if (!jitter) return d
            // full jitter: [0, d]
            return (d <= 0) ? 0 : (long) (Math.random() * d)
        }
    }

    @ImmutableOptions (knownImmutableClasses = Closure)
    static class RequestContext {
        String method
        URI uri
        Closure<HttpRequest> requestSupplier
        RetryPolicy retryPolicy
        int attempt = 1

        // Optional helper if user writes interceptor { ctx -> ctx.proceed() }
        Closure<CompletableFuture<HttpClientResponse>> proceed

        RequestContext(String method, URI uri, Closure<HttpRequest> requestSupplier, RetryPolicy retryPolicy) {
            this.method = method
            this.uri = uri
            this.requestSupplier = requestSupplier
            this.retryPolicy = retryPolicy
        }
    }

    /* =========================================================================
     * Circuit breaker + exceptions
     * ========================================================================= */

    static class CircuitBreaker {
        private final int failureThreshold
        private final long resetTimeoutMs
        private final AtomicInteger failureCount = new AtomicInteger(0)
        /** SEC-8: exactly one caller may hold the HALF_OPEN probe slot. */
        private final AtomicBoolean probeInFlight = new AtomicBoolean(false)

        // for spec visibility + time logic
        private volatile long openStateTime = 0L
        private volatile long lastFailureTime = 0L
        volatile State state = State.CLOSED

        // Factory used by CircuitBreakerSpec
        static CircuitBreaker create(SecurityConfig config) {
            assert config != null : "must call with non null SecurityConfig"
            new CircuitBreaker(config.failureThreshold ?: DEFAULT_FAILURE_THRESHOLD,
                    config.resetTimeoutMs ?: DEFAULT_RESET_TIMEOUT_MS)
        }

        CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
            this.failureThreshold = Math.max(1, failureThreshold)
            this.resetTimeoutMs = Math.max(1L, resetTimeoutMs)
        }

        /** Spec helper: after timeout, allow a HALF_OPEN probe */
        synchronized boolean canAttemptProbe() {
            if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                if (state != State.HALF_OPEN) {
                    state = State.HALF_OPEN
                    probeInFlight.set(false)
                }
                return true
            }
            false
        }

        /** Spec helper */
        synchronized void recordSuccess() {
            failureCount.set(0)
            openStateTime = 0L
            state = State.CLOSED
            probeInFlight.set(false)
        }

        /** Spec helper: opens after threshold; HALF_OPEN failure re-opens immediately */
        synchronized void recordFailure() {
            int currentFailures = failureCount.incrementAndGet()
            lastFailureTime = System.currentTimeMillis()

            // threshold reached, or the HALF_OPEN probe failed: (re)open
            if (currentFailures >= failureThreshold || state == State.HALF_OPEN) {
                openStateTime = System.currentTimeMillis()
                state = State.OPEN
                probeInFlight.set(false)
            }
        }

        /** Spec helper: auto-reset after timeout */
        synchronized boolean isOpen() {
            if (openStateTime > 0L) {
                if (System.currentTimeMillis() - openStateTime > resetTimeoutMs) {
                    reset()
                    return false
                }
                return true
            }
            return (failureCount.get() >= failureThreshold) || state == State.OPEN
        }

        /** Spec helper */
        synchronized void reset() {
            failureCount.set(0)
            openStateTime = 0L
            // keep lastFailureTime for probe logic if spec checks it; otherwise OK
            state = State.CLOSED
            probeInFlight.set(false)
        }

        // --- Methods used by GroovyHttpClient runtime path (keep your existing calls working) ---

        boolean tryAcquirePermission() {
            State s = state
            if (s == State.CLOSED) return true
            if (s == State.OPEN && !canAttemptProbe()) return false   // still cooling down
            // HALF_OPEN: only the caller that wins the slot proceeds; the rest are rejected until it resolves
            probeInFlight.compareAndSet(false, true)
        }

        void onSuccess() { recordSuccess() }

        void onError(Throwable t) {
            // keep your “only count transient/5xx” behavior for the *client* path
            boolean count =
                    isTransient(t) ||
                            (t instanceof HttpResponseException && ((HttpResponseException) t).statusCode >= 500)
            if (count) recordFailure()
            else if (state == State.HALF_OPEN) {
                // if the probe failed with a non-counted error, still reopen (probe semantics)
                recordFailure()
            }
        }

        // getters used by specs
        int getFailureThreshold() { failureThreshold }
        long getResetTimeoutMs() { resetTimeoutMs }
        int getFailureCount() { failureCount.get() }
        long getOpenStateTime() { openStateTime }
        long getLastFailureTime() { lastFailureTime }
    }

    static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String message) { super(message) }
    }

    static class HttpResponseException extends RuntimeException {
        static final int MESSAGE_BODY_CHARS = 120
        final int statusCode
        /** The full response body. Kept out of the message (SEC-11) so logging the exception does not log it. */
        final String responseBody

        HttpResponseException(int statusCode, String responseBody) {
            super(describe(statusCode, responseBody))
            this.statusCode = statusCode
            this.responseBody = responseBody
        }

        private static String describe(int status, String body) {
            if (!body) return 'HTTP error: ' + status
            String flat = body.replaceAll(/\p{Cntrl}+/, ' ').trim()
            String shown = flat.length() > MESSAGE_BODY_CHARS ? flat.substring(0, MESSAGE_BODY_CHARS) + '...' : flat
            'HTTP error: ' + status + ' (' + body.length() + ' chars), body starts: ' + shown
        }
    }

    /** RFC 6265 cookie name / value validation (SEC-7). */
    @CompileStatic
    static final class Cookies {
        private static final String SEPARATORS = '()<>@,;:"/[]?={}'

        static boolean isToken(String s) {
            if (!s) return false
            for (int i = 0; i < s.length(); i++) {
                int c = (int) s.charAt(i)
                if (c <= 0x20 || c >= 0x7F || c == 0x5C || SEPARATORS.indexOf(c) >= 0) return false
            }
            true
        }

        static boolean isCookieValue(String v) {
            if (v == null) return false
            String s = (v.length() >= 2 && v.startsWith('"') && v.endsWith('"')) ? v.substring(1, v.length() - 1) : v
            for (int i = 0; i < s.length(); i++) {
                int c = (int) s.charAt(i)
                boolean ok = c == 0x21 || (c >= 0x23 && c <= 0x2B) || (c >= 0x2D && c <= 0x3A) ||
                        (c >= 0x3C && c <= 0x5B) || (c >= 0x5D && c <= 0x7E)
                if (!ok) return false
            }
            true
        }

        static void validate(String name, String value) {
            if (!isToken(name)) throw new IllegalArgumentException('Invalid cookie name: ' + name)
            if (!isCookieValue(value)) {
                throw new IllegalArgumentException("Invalid value for cookie '" + name +
                        "': only RFC 6265 cookie-octets are allowed (no ; , whitespace, quotes, backslash or control characters)")
            }
        }

        static String pair(String name, String value) {
            validate(name, value)
            name + '=' + value
        }
    }

    static class ContentParseException extends RuntimeException {
        ContentParseException(String message) { super(message) }
    }

    /** A redirect was refused by policy: host not allowed, HTTPS to HTTP, bad scheme, or too many hops. */
    static class RedirectRejectedException extends RuntimeException {
        RedirectRejectedException(String message) { super(message) }
    }
}