package org.softwood.http

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.ImmutableOptions
import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import org.softwood.http.codec.BuiltinCodecs
import org.softwood.http.codec.CodecRegistry
import org.softwood.http.codec.MediaType
import org.softwood.http.compression.ContentDecoder
import org.softwood.http.compression.DecodedResponse
import org.softwood.http.compression.GzipRequestBody
import org.softwood.http.streaming.LineStreamTarget
import org.softwood.http.streaming.StoppedResponse
import org.softwood.http.streaming.StreamTarget

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
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

    /** Encoders for request bodies and parsers for responses, by media type (2.1). Immutable, shared. */
    final CodecRegistry codecs

    /** Client-level response { } handlers; each request's own block is merged over them (2.1). */
    private volatile ResponseHandlers defaultResponseHandlers

    /** Default response { } handlers for every request; a request's own handlers win per key. Null clears them. */
    GroovyHttpClient defaultResponse(@DelegatesTo(value = ResponseHandlers, strategy = Closure.DELEGATE_FIRST) Closure handlers) {
        defaultResponseHandlers = handlers == null ? null : ResponseHandlers.from(handlers)
        this
    }

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
        private java.security.KeyStore trustStore
        private javax.net.ssl.KeyManager[] keyManagers
        private Integer maxRedirects
        private Object httpVersion
        private Http3Discovery http3Discovery
        private CodecRegistry codecRegistry
        private CodecRegistry.Builder codecBuilder
        private Integer errorBodyPreviewChars
        private Boolean compression
        private Long maxStreamItemBytes
        private Closure defaultResponse

        Builder(String baseUrl) { this.baseUrl = baseUrl }

        /**
         * Register encoders and parsers by media type (2.1):
         * <pre>codecs { parser 'application/vnd.tmf+json', { String text -> ... }; jackson myMapper }</pre>
         * Built-ins (JSON, XML, text, form, CSV) and any ServiceLoader provider (Jackson) stay registered;
         * yours win at the same match rank.
         */
        Builder codecs(@DelegatesTo(value = CodecRegistry.Builder, strategy = Closure.DELEGATE_FIRST) Closure c) {
            if (codecBuilder == null) codecBuilder = CodecRegistry.builder()
            Closure dsl = (Closure) c.clone()
            dsl.delegate = codecBuilder
            dsl.resolveStrategy = Closure.DELEGATE_FIRST
            dsl.call(codecBuilder)
            this
        }

        /** Use a registry built elsewhere (shared between clients). */
        Builder codecs(CodecRegistry registry) { this.codecRegistry = registry; this }

        /** Characters of the error body shown in HttpResponseException messages (default 0 = none; 0..4096). */
        Builder errorBodyPreviewChars(int chars) { this.errorBodyPreviewChars = chars; this }

        /** Response compression (default off): send Accept-Encoding gzip, deflate and decode those bodies. */
        Builder compression(boolean enabled) { this.compression = enabled; this }

        /** Largest single line / SSE event / NDJSON value a stream consumer may receive (default 1 MiB). */
        Builder maxStreamItemBytes(long bytes) { this.maxStreamItemBytes = bytes; this }

        /** Client-level response { } handlers (success / failure / when / exception); requests merge over them. */
        Builder response(@DelegatesTo(value = ResponseHandlers, strategy = Closure.DELEGATE_FIRST) Closure handlers) {
            this.defaultResponse = handlers
            this
        }

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
        /** Trust exactly the certificates in this KeyStore (private CA, self-signed server). */
        Builder trustStore(java.security.KeyStore ks) { this.trustStore = ks; this }
        /** Client certificate(s) for mutual TLS. */
        Builder keyManagers(javax.net.ssl.KeyManager[] kms) { this.keyManagers = kms; this }
        /** Preferred HTTP version: an {@link HttpVersion} or a name such as 'HTTP/1.1', 'HTTP_2', 'HTTP/3'. */
        Builder httpVersion(Object v) { this.httpVersion = v; this }
        /**
         * Use HTTP/3 (JDK 26+), with the given discovery mode (default {@link Http3Discovery#ANY}: fall back to
         * HTTP/2 when the server or the network doesn't do QUIC).
         */
        Builder http3(Http3Discovery mode = Http3Discovery.ANY) { this.http3Discovery = mode; this.httpVersion = HttpVersion.HTTP_3; this }

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
                    trustStore                 : trustStore,
                    keyManagers                : keyManagers,
                    maxRedirects               : maxRedirects,
                    httpVersion                : httpVersion,
                    http3Discovery             : http3Discovery,
                    errorBodyPreviewChars      : errorBodyPreviewChars,
                    compression                : compression,
                    maxStreamItemBytes         : maxStreamItemBytes
            ])
            def client = new GroovyHttpClient(cfg, codecRegistry ?: codecBuilder?.build())
            if (defaultResponse != null) client.defaultResponse(defaultResponse)
            client
        }
    }

    static Builder builder(String baseUrl) { new Builder(baseUrl) }

    private static final Set<String> FIELD_NAMES =
            Collections.unmodifiableSet(GroovyHttpClient.declaredFields.collect { it.name } as Set<String>)
    private static final Set<String> BUILDER_FIELDS =
            Collections.unmodifiableSet(GroovyRequestBuilder.declaredFields.collect { it.name } as Set<String>)
    private static final MetaClass CLIENT_META = org.codehaus.groovy.runtime.InvokerHelper.getMetaClass(GroovyHttpClient)

    // Called qualified from GroovyRequestBuilder.getProperty: an inner class reads outer private statics through
    // getProperty, which would re-enter it.
    private static boolean isBuilderField(String name) { BUILDER_FIELDS.contains(name) }

    private static boolean isClientName(String name) { FIELD_NAMES.contains(name) || CLIENT_META.getMetaProperty(name) != null }

    /**
     * Unknown property names fail with MissingPropertyException (2.1). Groovy otherwise falls back to a class's
     * {@code get(String)} method as a generic getter -- and here that is the HTTP GET verb: before 2.1
     * {@code client.foo}, or a caller's constant or variable referenced inside a request closure, sent
     * {@code GET /foo} and evaluated to its CompletableFuture.
     */
    @Override
    Object getProperty(String name) {
        MetaClass mc = getMetaClass()
        if (FIELD_NAMES.contains(name) || mc.getMetaProperty(name) != null) {
            return mc.getProperty(this, name)
        }
        throw new MissingPropertyException(name, GroovyHttpClient)
    }

    /* =========================================================================
     * Construction
     * ========================================================================= */

    /**
     * Client with {@link SecurityConfig#defaults(String)}: certificates and host names verified.
     * (Before 2.0.0 this used the trust-all testing profile.)
     */
    GroovyHttpClient(String baseUrl) { this(SecurityConfig.defaults(baseUrl)) }

    /** True when the running JDK supports HTTP/3 in java.net.http (JDK 26+, JEP 517). */
    static boolean isHttp3Available() { Http3Support.isAvailable() }

    /** The HTTP version this client prefers on this JDK, after any HTTP/3 fallback. */
    HttpVersion getHttpVersion() { effectiveHttpVersion }

    private final HttpVersion effectiveHttpVersion

    /** @param codecs the codec registry; null = {@link CodecRegistry#defaults()} (built-ins + ServiceLoader providers) */
    GroovyHttpClient(SecurityConfig config, CodecRegistry codecs = null) {
        if (config == null) throw new IllegalArgumentException("SecurityConfig is required")

        this.config = config
        this.codecs = codecs ?: CodecRegistry.defaults()
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

        HttpClient.Version jdkVersion = Http3Support.toJdk(config.httpVersion ?: HttpVersion.HTTP_2)
        if (jdkVersion == null) {
            // HTTP_3 requested on a JDK without it (< 26)
            if (config.http3Discovery == Http3Discovery.HTTP_3_URI_ONLY) {
                throw new IllegalStateException("HTTP/3-only (Http3Discovery.HTTP_3_URI_ONLY) requires JDK 26 or later; running Java ${Runtime.version()}")
            }
            log.warn("[GroovyHttpClient] HTTP/3 requested but this JDK ({}) has no HTTP/3 support (needs 26+); using HTTP/2", Runtime.version())
            jdkVersion = HttpClient.Version.HTTP_2
        }
        this.effectiveHttpVersion = Http3Support.fromJdk(jdkVersion)

        def b = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(config.connectTimeout ?: DEFAULT_CONNECT_TIMEOUT)
                .version(jdkVersion)
                // Redirects are followed by sendFollowingRedirects so every hop is checked against
                // allowedHosts (SEC-3); the JDK client must never follow them itself.
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookieManager)
                .proxy(ProxySelector.getDefault())

        if (config.insecureAllowed) {
            this.httpClient = createInsecureClient(b, config)
            log.warn("[GroovyHttpClient] Certificate and host-name verification DISABLED (SecurityConfig insecureAllowed / trustAll) for {}. Never use this in production.", config.baseUrl)
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

    /**
     * The request DSL delegate. Public since 2.1: as a private class, @CompileStatic callers of any verb's
     * closure failed at runtime with IllegalAccessError.
     */
    class GroovyRequestBuilder {
        private final HttpRequest.Builder builder
        private final Map<String, List<String>> headers = new ConcurrentHashMap<String, List<String>>()
        private final Map<String, List<String>> queryParams = new ConcurrentHashMap<String, List<String>>()
        private RetryPolicy retryOverride
        // C-1: per-request settings recorded here and replayed onto every (re)built request
        private Duration timeoutOverride
        private HttpClient.Version versionOverride
        private boolean expectContinueOverride = false
        private HttpVersion httpVersionOverride
        private Http3Discovery http3DiscoveryOverride

        private HttpRequest.BodyPublisher bodyPublisher = null
        private String bodyContentType = null
        private ResponseHandlers responseHandlers = null
        private Boolean compressionOverride = null
        private boolean gzipBodyRequested = false
        private Long maxResponseBytesOverride = null
        private StreamTarget streamTarget = null
        private LineStreamTarget.Kind streamKind = null
        private Closure streamConsumer = null
        private Duration idleTimeoutOverride = null
        private boolean oneShotBody = false

        GroovyRequestBuilder(HttpRequest.Builder builder) { this.builder = builder }

        /**
         * A name that is neither a property of this builder nor a real field or property of the client is the
         * caller's: fail here so the closure's DELEGATE_FIRST lookup falls back to its owner (2.1 fix). Groovy's
         * own lookup for an inner class asks the enclosing client's metaclass, where get(String) -- the GET
         * verb -- served as a generic getter, so `query(a: SOME_CONSTANT)` used to send GET /SOME_CONSTANT.
         */
        @Override
        Object getProperty(String name) {
            MetaClass mc = getMetaClass()
            if (mc.getMetaProperty(name) != null || GroovyHttpClient.isBuilderField(name)) {
                return mc.getProperty(this, name)
            }
            if (GroovyHttpClient.isClientName(name)) {
                return GroovyHttpClient.this.getProperty(name)     // a real client field / property (e.g. codecs)
            }
            throw new MissingPropertyException(name, GroovyRequestBuilder)
        }

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

        /** Per-request HTTP version preference using the library enum (HTTP_3 needs JDK 26+; falls back to HTTP_2). */
        GroovyRequestBuilder httpVersion(HttpVersion v) { httpVersionOverride = v; this }

        /** Use HTTP/3 for this request, with the given discovery mode (default ANY). */
        GroovyRequestBuilder http3(Http3Discovery mode = Http3Discovery.ANY) {
            httpVersionOverride = HttpVersion.HTTP_3
            http3DiscoveryOverride = mode
            this
        }

        /** Send 'Expect: 100-continue' for this request. */
        GroovyRequestBuilder expectContinue(boolean enable) { expectContinueOverride = enable; this }

        /** Response compression for this request only; overrides the client setting (2.1). */
        GroovyRequestBuilder compression(boolean enabled) { compressionOverride = enabled; this }

        /**
         * Send this request's body gzip-compressed with Content-Encoding: gzip (2.1). Never automatic: many servers
         * refuse compressed requests. Works for every body (codecs, form, multipart); no effect without a body.
         */
        GroovyRequestBuilder gzipBody() { gzipBodyRequested = true; this }

        Boolean getCompressionOrNull() { compressionOverride }
        boolean isGzipBodyRequested() { gzipBodyRequested }

        /** Response size limit for this request only; overrides the client's maxResponseBytes (2.1). */
        GroovyRequestBuilder maxResponseBytes(long bytes) {
            if (bytes <= 0) throw new IllegalArgumentException("maxResponseBytes must be positive (was ${bytes})")
            maxResponseBytesOverride = bytes
            this
        }

        /**
         * Stream a 2xx body to this file instead of memory (2.1). It is written to a temp file beside the target and
         * moved over it only when the download succeeds; a failure leaves an existing target untouched. Error bodies
         * stay in memory for HttpResponseException. maxResponseBytes counts the bytes written.
         */
        GroovyRequestBuilder downloadTo(Path target) { streamTo(new StreamTarget.PathTarget(target)) }

        GroovyRequestBuilder downloadTo(File target) { streamTo(new StreamTarget.PathTarget(target?.toPath())) }

        /** Stream a 2xx body into the caller's OutputStream (flushed, never closed). Not retried once a byte is written. */
        GroovyRequestBuilder downloadTo(OutputStream out) { streamTo(new StreamTarget.OutputStreamTarget(out)) }

        /** Internal: where this request's 2xx body is streamed. One target per request. */
        GroovyRequestBuilder streamTo(StreamTarget target) {
            if (streamTarget != null || streamConsumer != null) throw new IllegalStateException('A request can stream its body to one target only')
            streamTarget = target
            this
        }

        Long getMaxResponseBytesOrNull() { maxResponseBytesOverride }
        StreamTarget getStreamTargetOrNull() { streamTarget }

        /**
         * Deliver the 2xx body line by line as it arrives (2.1): { String line -> } or { String line, StreamControl c -> }.
         * Lines end at \n, \r\n or \r; the charset comes from Content-Type (UTF-8 default).
         */
        GroovyRequestBuilder lines(Closure consumer) { streamConsumer(LineStreamTarget.Kind.LINES, consumer) }

        /** Deliver Server-Sent Events as they arrive: { SseEvent e -> } or { SseEvent e, StreamControl c -> }. No reconnection. */
        GroovyRequestBuilder sse(Closure consumer) { streamConsumer(LineStreamTarget.Kind.SSE, consumer) }

        /** Deliver each NDJSON line parsed (Map / List / value); blank lines are skipped. */
        GroovyRequestBuilder ndjson(Closure consumer) { streamConsumer(LineStreamTarget.Kind.NDJSON, consumer) }

        /** Internal: declare a stream consumer. One stream target per request. */
        GroovyRequestBuilder streamConsumer(LineStreamTarget.Kind kind, Closure consumer) {
            if (consumer == null) throw new IllegalArgumentException('A stream consumer closure is required')
            if (streamTarget != null || streamConsumer != null) throw new IllegalStateException('A request can stream its body to one target only')
            streamKind = kind
            streamConsumer = consumer
            this
        }

        /**
         * Fail a response that delivers no bytes for this long with HttpTimeoutException (2.1, default off). The request
         * timeout covers the time to response headers; this covers the body, e.g. a stalled stream.
         */
        GroovyRequestBuilder idleTimeout(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException('idleTimeout must be a positive duration')
            }
            idleTimeoutOverride = duration
            this
        }

        LineStreamTarget.Kind getStreamKindOrNull() { streamKind }
        Closure getStreamConsumerOrNull() { streamConsumer }
        Duration getIdleTimeoutOrNull() { idleTimeoutOverride }

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
            body(params ?: [:], "application/x-www-form-urlencoded; charset=${charset.name()}".toString())
        }

        /** JSON encode + set Content-Type (a String is sent verbatim). */
        void json(Object obj, Charset charset = Charset.forName("UTF-8")) {
            body(obj, "application/json; charset=${charset.name()}".toString())
        }

        /**
         * Request body encoded by the client's codec registry for this Content-Type (2.1). Without a type:
         * byte[] = application/octet-stream, CharSequence = text/plain, anything else = application/json
         * (all UTF-8). A byte[] is always sent as is. IllegalArgumentException when no encoder handles the type.
         */
        void body(Object obj, String contentType = null) {
            String ct = contentType ?: defaultContentType(obj)
            MediaType type = MediaType.parse(ct)
            byte[] bytes = obj instanceof byte[] ? (byte[]) obj : codecs.encode(obj, type)
            bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes)
            bodyContentType = ct
            oneShotBody = false
        }

        /**
         * Stream a file as the request body (2.1): sent with its Content-Length and read as it is sent, never held in
         * memory. Replayable, so retries and 307/308 redirects re-send it. Content type: as given, else probed from the
         * file name, else application/octet-stream. A missing file fails here, before anything is sent.
         */
        void body(Path file, String contentType = null) {
            if (file == null) throw new IllegalArgumentException('body file must not be null')
            if (!Files.isRegularFile(file)) throw new FileNotFoundException("Upload file not found: ${file}".toString())
            bodyPublisher = HttpRequest.BodyPublishers.ofFile(file)
            bodyContentType = contentType ?: (probeContentType(file) ?: 'application/octet-stream')
            oneShotBody = false
        }

        void body(File file, String contentType = null) { body(file?.toPath(), contentType) }

        /**
         * Stream an InputStream as the request body (2.1), chunked; the caller closes it. One-shot: the request is
         * never retried, whatever the retry policy says, and a 307/308 redirect fails with RedirectRejectedException.
         */
        void body(InputStream input, String contentType) {
            if (input == null) throw new IllegalArgumentException('body stream must not be null')
            AtomicBoolean used = new AtomicBoolean(false)
            bodyPublisher = HttpRequest.BodyPublishers.ofInputStream({ ->
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException('A one-shot request body (InputStream) cannot be sent twice')
                }
                input
            } as Supplier<InputStream>)
            bodyContentType = contentType ?: 'application/octet-stream'
            oneShotBody = true
        }

        /**
         * Stream a Flow.Publisher as the request body (2.1), chunked. One-shot, like an InputStream body. The client
         * subscribes when the request is sent: a hot publisher (e.g. SubmissionPublisher) drops what it emits before then.
         */
        void body(Flow.Publisher<ByteBuffer> publisher, String contentType) {
            if (publisher == null) throw new IllegalArgumentException('body publisher must not be null')
            bodyPublisher = HttpRequest.BodyPublishers.fromPublisher(publisher)
            bodyContentType = contentType ?: 'application/octet-stream'
            oneShotBody = true
        }

        boolean isOneShotBody() { oneShotBody }

        private static String probeContentType(Path file) {
            try {
                return Files.probeContentType(file)
            } catch (IOException ignored) {
                return null
            }
        }

        /**
         * Turn the outcome into a value instead of an exception (2.1):
         * <pre>response { success { r -> r as Order }; when(404) { null }; failure { r -> ... }; exception { t -> ... } }</pre>
         * See {@link ResponseHandlers} for precedence. Retries and the circuit breaker run first, unchanged.
         */
        void response(@DelegatesTo(value = ResponseHandlers, strategy = Closure.DELEGATE_FIRST) Closure handlers) {
            responseHandlers = ResponseHandlers.from(handlers)
        }

        ResponseHandlers getResponseHandlersOrNull() { responseHandlers }

        private String defaultContentType(Object obj) {
            if (obj instanceof byte[]) return 'application/octet-stream'
            if (obj instanceof CharSequence) return 'text/plain; charset=UTF-8'
            'application/json; charset=UTF-8'
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
        HttpRequest.Builder applyTo(HttpRequest.Builder b, Duration defaultTimeout,
                                    HttpVersion clientVersion = null, Http3Discovery clientDiscovery = null) {
            b.timeout(timeoutOverride ?: defaultTimeout)
            if (versionOverride != null) b.version(versionOverride)
            HttpVersion effective = httpVersionOverride ?: clientVersion
            Http3Discovery discovery = http3DiscoveryOverride ?: clientDiscovery
            if (httpVersionOverride != null) {
                def v = Http3Support.toJdk(httpVersionOverride)
                if (v == null) {   // per-request HTTP/3 on a JDK without it
                    if (discovery == Http3Discovery.HTTP_3_URI_ONLY) {
                        throw new IllegalStateException("HTTP/3-only request requires JDK 26 or later; running Java ${Runtime.version()}")
                    }
                    v = HttpClient.Version.HTTP_2
                    effective = HttpVersion.HTTP_2
                }
                b.version(v)
            }
            if (effective == HttpVersion.HTTP_3 && discovery != null) Http3Support.applyDiscovery(b, discovery)
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
        /** The protocol version the exchange actually used (e.g. HTTP_3 when QUIC was negotiated). */
        final HttpVersion httpVersion
        /** The Content-Encoding the server applied (e.g. 'gzip'), or null; set whether or not the body was decoded (2.1). */
        final String contentEncoding
        /** True when the client decoded the body (compression on); Content-Encoding and Content-Length are then dropped (2.1). */
        final boolean decoded
        /** For a streamed request (downloadTo, lines, sse, ndjson): what went to the target. The body here is then empty. */
        StreamResult streamResult

        /** The client's codec registry; parsed / bodyAs / as use it (defaults when built outside a client). */
        final CodecRegistry codecs

        HttpClientResponse(HttpResponse<byte[]> response, CodecRegistry codecs = null) {
            this.codecs = codecs ?: CodecRegistry.defaults()
            this.httpVersion = Http3Support.fromJdk(response.version())
            this.bytes = response.body() ?: new byte[0]
            this.headers = response.headers().map()
            this.decoded = response instanceof DecodedResponse
            this.contentEncoding = decoded ? ((DecodedResponse) response).contentEncoding :
                    headers.find { k, v -> k.equalsIgnoreCase('Content-Encoding') }?.value?.getAt(0)
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

        /** The Content-Type as a MediaType, or null when absent or malformed. */
        MediaType getMediaType() { MediaType.parseOrNull(contentType) }

        private volatile Object parsedCache
        private volatile boolean parsedTried = false

        /** The body parsed by the registry for its Content-Type (JSON Map/List, XML GPathResult, CSV rows, text...). */
        Object getParsed() {
            if (!parsedTried) {
                parsedCache = codecs.parse(bytes, mediaType, Object)
                parsedTried = true
            }
            parsedCache
        }

        /** The body as an instance of {@code type} (Jackson when present, else Groovy coercion). */
        def <T> T bodyAs(Class<T> type) { (T) codecs.parse(bytes, mediaType, type) }

        private transient volatile HttpResponseException raised

        @groovy.transform.PackageScope
        void attachException(HttpResponseException e) { raised = e }

        /**
         * The HttpResponseException the client raises for this response (status >= 400), with method and URI when
         * the client raised it; null below 400. For {@code failure { r -> throw r.toException() }}.
         */
        HttpResponseException toException() {
            raised ?: (statusCode >= 400 ? new HttpResponseException(this, null, null, 0) : null)
        }

        /** RFC 9457 problem details when the Content-Type is application/problem+json and it parses; else null. */
        ProblemDetail getProblem() {
            if (mediaType?.essence != 'application/problem+json') return null
            try {
                def p = parsed
                return p instanceof Map ? ProblemDetail.from((Map) p) : null
            } catch (RuntimeException ignored) {
                return null
            }
        }

        /** {@code response as Customer} = {@link #bodyAs}; the response itself for its own types. */
        Object asType(Class type) {
            if (type == null || type.isInstance(this)) return this
            if (type == String) return body
            bodyAs(type)
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
        cookie.version = 0   // RFC 6265: java.net.HttpCookie defaults to RFC 2965 ($Version="1"; name="value"; ...)
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

    /* ---- Downloads (2.1): a 2xx body goes to a file or stream, never into memory -------------------------- */

    /** GET path into target via a temp file moved over it on success. See GroovyRequestBuilder.downloadTo. */
    CompletableFuture<StreamResult> download(String path, Path target,
                                             @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        streamedGet(path, new StreamTarget.PathTarget(target), cfg)
    }

    CompletableFuture<StreamResult> download(String path, File target,
                                             @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        streamedGet(path, new StreamTarget.PathTarget(target?.toPath()), cfg)
    }

    /** GET path into the caller's stream (flushed, not closed). */
    CompletableFuture<StreamResult> download(String path, OutputStream out,
                                             @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        streamedGet(path, new StreamTarget.OutputStreamTarget(out), cfg)
    }

    private CompletableFuture<StreamResult> streamedGet(String path, StreamTarget target, Closure cfg) {
        send("GET", path, null, cfg, { GroovyRequestBuilder b -> b.streamTo(target) })
                .thenApply { Object v -> (StreamResult) typedResult(v, StreamResult) } as CompletableFuture<StreamResult>
    }

    // Sync -- each throws the underlying failure (HttpResponseException, HttpTimeoutException,
    // IOException, CircuitOpenException, RedirectRejectedException...), not ExecutionException (2.0.0),
    // and java.util.concurrent.TimeoutException if the caller's own wait expires.
    // Not named await: Groovy 6 provides a built-in await(...) (async/await), which would take the call.
    private static <T> T awaitResult(CompletableFuture<T> future, Duration timeout) {
        try {
            // null: wait for completion (streamed requests; requestTimeout and idleTimeout still apply)
            return timeout == null ? future.get() : future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (java.util.concurrent.ExecutionException e) {
            throw unwrapCompletion(e.cause ?: e)
        }
    }

    /** Downloads wait for completion by default (timeout null); pass a Duration to bound the wait. */
    StreamResult downloadSync(String path, Path target, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = null) {
        awaitResult(download(path, target, cfg), timeout)
    }
    StreamResult downloadSync(String path, File target, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = null) {
        awaitResult(download(path, target, cfg), timeout)
    }
    StreamResult downloadSync(String path, OutputStream out, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = null) {
        awaitResult(download(path, out, cfg), timeout)
    }

    /* ---- Line / SSE / NDJSON streams (2.1): GET shortcuts; any verb can declare lines {} / sse {} / ndjson {} ---- */

    CompletableFuture<StreamResult> lines(String path, Closure consumer) { consumerGet(path, LineStreamTarget.Kind.LINES, null, consumer) }
    CompletableFuture<StreamResult> lines(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        consumerGet(path, LineStreamTarget.Kind.LINES, cfg, consumer)
    }
    CompletableFuture<StreamResult> sse(String path, Closure consumer) { consumerGet(path, LineStreamTarget.Kind.SSE, null, consumer) }
    CompletableFuture<StreamResult> sse(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        consumerGet(path, LineStreamTarget.Kind.SSE, cfg, consumer)
    }
    CompletableFuture<StreamResult> ndjson(String path, Closure consumer) { consumerGet(path, LineStreamTarget.Kind.NDJSON, null, consumer) }
    CompletableFuture<StreamResult> ndjson(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        consumerGet(path, LineStreamTarget.Kind.NDJSON, cfg, consumer)
    }

    /** Stream sync methods wait until the stream ends (or is stopped); requestTimeout / idleTimeout bound the wait. */
    StreamResult linesSync(String path, Closure consumer) { awaitResult(lines(path, consumer), null) }
    StreamResult linesSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        awaitResult(lines(path, cfg, consumer), null)
    }
    StreamResult sseSync(String path, Closure consumer) { awaitResult(sse(path, consumer), null) }
    StreamResult sseSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        awaitResult(sse(path, cfg, consumer), null)
    }
    StreamResult ndjsonSync(String path, Closure consumer) { awaitResult(ndjson(path, consumer), null) }
    StreamResult ndjsonSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg, Closure consumer) {
        awaitResult(ndjson(path, cfg, consumer), null)
    }

    private CompletableFuture<StreamResult> consumerGet(String path, LineStreamTarget.Kind kind, Closure cfg, Closure consumer) {
        send("GET", path, null, cfg, { GroovyRequestBuilder b -> b.streamConsumer(kind, consumer) })
                .thenApply { Object v -> (StreamResult) typedResult(v, StreamResult) } as CompletableFuture<StreamResult>
    }

    HttpClientResponse getSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(get(path, cfg), timeout))
    }
    HttpClientResponse postSync(String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(post(path, body, cfg), timeout))
    }
    HttpClientResponse putSync(String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(put(path, body, cfg), timeout))
    }
    HttpClientResponse patchSync(String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(patch(path, body, cfg), timeout))
    }
    HttpClientResponse deleteSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(delete(path, cfg), timeout))
    }
    HttpClientResponse headSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(head(path, cfg), timeout))
    }
    HttpClientResponse optionsSync(String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        responseOrFail(awaitResult(options(path, cfg), timeout))
    }

    // Typed results (2.1): the body mapped to type by the codec registry. A >= 400 still raises.
    def <T> CompletableFuture<T> get(Class<T> type, String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        get(path, cfg).thenApply { Object v -> typedResult(v, type) }
    }
    def <T> CompletableFuture<T> post(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        post(path, body, cfg).thenApply { Object v -> typedResult(v, type) }
    }
    def <T> CompletableFuture<T> put(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        put(path, body, cfg).thenApply { Object v -> typedResult(v, type) }
    }
    def <T> CompletableFuture<T> patch(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        patch(path, body, cfg).thenApply { Object v -> typedResult(v, type) }
    }
    def <T> CompletableFuture<T> delete(Class<T> type, String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {
        delete(path, cfg).thenApply { Object v -> typedResult(v, type) }
    }

    def <T> T getSync(Class<T> type, String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(get(type, path, cfg), timeout)
    }
    def <T> T postSync(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(post(type, path, body, cfg), timeout)
    }
    def <T> T putSync(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(put(type, path, body, cfg), timeout)
    }
    def <T> T patchSync(Class<T> type, String path, String body, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(patch(type, path, body, cfg), timeout)
    }
    def <T> T deleteSync(Class<T> type, String path, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(delete(type, path, cfg), timeout)
    }

    /* =========================================================================
     * Core send (retry + interceptor + circuit breaker)
     * ========================================================================= */

    private CompletableFuture<HttpClientResponse> send(
            String method,
            String path,
            String body = null,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null,
            Closure preset = null
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

        // What a convenience verb declares (download, lines...), before the caller's closure
        if (preset != null) preset.call(wrapped)

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

        boolean decodeResponse = prepareCompression(wrapped)
        pub = gzipIfRequested(wrapped, pub)

        def requestSupplier = { ->
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(finalUri)
            // C-1: replay the per-request DSL settings (timeout, version, expectContinue, headers)
            wrapped.applyTo(b, config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT, effectiveHttpVersion, config.http3Discovery)

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
        ctx.decodeResponse = decodeResponse
        ctx.maxResponseBytes = wrapped.getMaxResponseBytesOrNull() ?: maxResponseBytes
        ctx.streamTarget = streamTargetFor(wrapped)
        ctx.idleTimeoutMs = wrapped.getIdleTimeoutOrNull()?.toMillis() ?: 0L
        ctx.oneShotBody = wrapped.isOneShotBody()

        def chain = buildInterceptorChain()

        // Circuit breaker gates the overall operation
        def outcome = executeWithCircuitBreaker {
            attemptWithRetry(ctx, chain).thenApply { HttpClientResponse r -> raiseForStatus(r, ctx) }
        }
        if (ctx.streamTarget != null) outcome = finishStream(outcome, ctx.streamTarget)
        ResponseHandlers own = wrapped.getResponseHandlersOrNull()
        ResponseHandlers handlers = own != null ? own.mergedOver(defaultResponseHandlers) : defaultResponseHandlers
        return (handlers == null || handlers.empty) ? outcome : applyResponseHandlers(outcome, handlers)
    }

    /**
     * Runs response { } handlers on the final outcome (after retries and the circuit breaker). A status >= 400 arrives
     * here as HttpResponseException carrying its response; with no handler for its status it is raised as before.
     * exception { } receives every other failure. A handler's return value completes the future; a throw fails it.
     */
    private CompletableFuture<Object> applyResponseHandlers(CompletableFuture<HttpClientResponse> outcome, ResponseHandlers h) {
        outcome.handle { HttpClientResponse r, Throwable t ->
            if (t == null) {
                Closure c = h.select(r.statusCode)
                return c == null ? r : ResponseHandlers.call(c, r)
            }
            Throwable cause = unwrapCompletion(t)
            if (cause instanceof HttpResponseException && ((HttpResponseException) cause).response != null) {
                HttpResponseException hre = (HttpResponseException) cause
                Closure c = h.select(hre.statusCode)
                if (c != null) return ResponseHandlers.call(c, hre.response)
                throw cause
            }
            if (h.exceptionHandler != null) return ResponseHandlers.call(h.exceptionHandler, cause)
            throw cause
        } as CompletableFuture<Object>
    }

    /** A typed verb's result: the handler's value when it is already a T (or null), else the response body as T. */
    private static Object typedResult(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return value
        if (value instanceof HttpClientResponse && type == StreamResult) {
            StreamResult s = ((HttpClientResponse) value).streamResult
            if (s == null) throw new IllegalStateException('StreamResult was asked for, but the request declared no stream target (e.g. downloadTo)')
            return s
        }
        if (value instanceof HttpClientResponse) return ((HttpClientResponse) value).bodyAs(type)
        throw new ClassCastException("A response handler returned ${value.getClass().name}; the call asked for ${type.name}")
    }

    /** Untyped sync verbs return HttpClientResponse (2.0 signatures); a handler value needs the typed verb. */
    private static HttpClientResponse responseOrFail(Object value) {
        if (value == null || value instanceof HttpClientResponse) return (HttpClientResponse) value
        String n = value.getClass().simpleName
        throw new IllegalStateException("A response handler returned ${value.getClass().name}: call the typed verb, e.g. getSync(${n}, path) " +
                "or getSync(Object, path), to receive handler values")
    }

    private Closure<CompletableFuture<HttpClientResponse>> buildInterceptorChain() {
        Closure<CompletableFuture<HttpClientResponse>> terminal = { RequestContext ctx ->
            HttpRequest req = ctx.requestSupplier.call()
            long started = System.nanoTime()
            sendFollowingRedirects(req, 0, ctx)
                    .thenApply { HttpResponse<byte[]> resp ->
                        if (config.enableLogging) {
                            log.info("[GroovyHttpClient] {} {} -> {} in {} ms (attempt {}, {})", ctx.method, loggableUri(resp.uri()),
                                    resp.statusCode(), (System.nanoTime() - started).intdiv(1_000_000L), ctx.attempt, resp.version())
                        }
                        // byte-accurate maxResponseBytes
                        long limit = ctx.maxResponseBytes ?: maxResponseBytes
                        if (limit != Long.MAX_VALUE && resp.body() != null && resp.body().length > limit) {
                            throw new ResponseTooLargeException(limit, resp.body().length, ctx.method, ctx.uri)
                        }

                        // >= 400 is raised AFTER retry (raiseForStatus), so status-based retry can see the
                        // response (SEC-4). Interceptors now receive 4xx/5xx responses rather than exceptions.
                        new HttpClientResponse(resp, codecs)
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

    /**
     * Response compression for one request (2.1): on when the request says so, else when the client does. Advertises
     * exactly what the client decodes. A caller who set Accept-Encoding gets the body as sent, undecoded.
     */
    private boolean prepareCompression(GroovyRequestBuilder w) {
        Boolean own = w.getCompressionOrNull()
        boolean on = own != null ? own.booleanValue() : config.compression
        if (!on || w.hasHeader('Accept-Encoding')) return false
        w.header('Accept-Encoding', ContentDecoder.ACCEPT_ENCODING)
        true
    }

    private static HttpRequest.BodyPublisher gzipIfRequested(GroovyRequestBuilder w, HttpRequest.BodyPublisher pub) {
        if (pub == null || !w.isGzipBodyRequested()) return pub
        w.setHeader('Content-Encoding', 'gzip')
        GzipRequestBody.wrap(pub)
    }

    /** The request's stream target: a download target, or a line / SSE / NDJSON consumer bounded by maxStreamItemBytes. */
    private StreamTarget streamTargetFor(GroovyRequestBuilder w) {
        StreamTarget t = w.getStreamTargetOrNull()
        if (t != null) return t
        Closure c = w.getStreamConsumerOrNull()
        c == null ? null : new LineStreamTarget(w.getStreamKindOrNull(), c, config.maxStreamItemBytes)
    }

    /** Commit a streamed body once the request succeeded (after retries and status checks); discard it otherwise. */
    private static CompletableFuture<HttpClientResponse> finishStream(CompletableFuture<HttpClientResponse> outcome, StreamTarget target) {
        outcome.handle { HttpClientResponse r, Throwable t ->
            if (t != null) {
                target.abort()
                throw (t instanceof CompletionException ? (CompletionException) t : new CompletionException(t))
            }
            target.commit()
            r.streamResult = target.result(r)
            r
        } as CompletableFuture<HttpClientResponse>
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
                // a stream target that has handed bytes to the caller cannot be replayed (2.1)
                boolean again = retryable && attempt.get() < policy.maxAttempts &&
                        (ctx.streamTarget == null || ctx.streamTarget.retryable) && !ctx.oneShotBody &&
                        (cause == null ? policy.shouldRetry(null, resp) : policy.shouldRetry(cause, null))
                if (again) {
                    long delayMs = policy.nextDelayMs(attempt.get())
                    if (config.enableLogging) {
                        log.info("[GroovyHttpClient] retrying {} {} in {} ms (attempt {} of {}) after {}", ctx.method,
                                loggableUri(ctx.uri), delayMs, attempt.get() + 1, policy.maxAttempts,
                                cause == null ? "status ${resp?.statusCode}" : cause.class.simpleName)
                    }
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

    private HttpClientResponse raiseForStatus(HttpClientResponse r, RequestContext ctx) {
        if (r.statusCode >= 400) throw new HttpResponseException(r, ctx.method, ctx.uri, config.errorBodyPreviewChars)
        r
    }

    private static Throwable unwrapCompletion(Throwable t) {
        Throwable cause = t
        while (cause != null) {
            if (cause instanceof CompletionException && cause.cause != null) { cause = cause.cause; continue }
            // The JDK client wraps a BodySubscriber failure in IOException. Our own exceptions (e.g. the
            // 413 from BoundedBodySubscriber) must surface as themselves, not as a retryable I/O error.
            if (cause instanceof IOException && (cause.cause instanceof HttpResponseException ||
                    cause.cause instanceof RedirectRejectedException || cause.cause instanceof ContentDecodingException)) {
                cause = cause.cause; continue
            }
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

    /**
     * URI for log lines: scheme, host, port and path only. The query string and user-info are dropped
     * because they routinely carry tokens. Headers and bodies are never logged.
     */
    static String loggableUri(URI u) {
        if (u == null) return 'null'
        String port = u.port != -1 ? ':' + u.port : ''
        (u.scheme ?: '') + '://' + (u.host ?: '') + port + (u.rawPath ?: '')
    }

    /* =========================================================================
     * Redirects (SEC-3 / SEC-12): followed here, never by the JDK client, so every
     * hop is policy-checked and credentials do not cross origins
     * ========================================================================= */

    private static final Set<Integer> REDIRECT_CODES = [301, 302, 303, 307, 308] as Set<Integer>
    private static final Set<String> CREDENTIAL_HEADERS = ['authorization', 'proxy-authorization', 'cookie'] as Set<String>

    private CompletableFuture<HttpResponse<byte[]>> sendFollowingRedirects(HttpRequest req, int hop, RequestContext ctx = null) {
        // HTTP/2 and HTTP/3 fail a cancelled stream with their own cause-less IOException, so the body subscriber's
        // own failure (size limit, undecodable body) travels in a side channel and replaces it (2.0 surfaced a
        // retryable IOException there).
        def failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>()
        def decodedAs = ctx?.decodeResponse ? new java.util.concurrent.atomic.AtomicReference<String>() : null
        long limit = ctx?.maxResponseBytes ?: maxResponseBytes
        // A consumer's stop() cancels the body; HTTP/2 and HTTP/3 then fail the exchange, so the head travels here.
        def stoppedAt = new java.util.concurrent.atomic.AtomicReference<HttpResponse.ResponseInfo>()
        httpClient.sendAsync(req, boundedBodyHandler(req, failure, decodedAs, limit, ctx?.streamTarget, ctx?.idleTimeoutMs ?: 0L, stoppedAt))
                .handle { HttpResponse<byte[]> r, Throwable err ->
            HttpResponse.ResponseInfo stopped = stoppedAt.get()
            if (err == null || stopped != null) {
                HttpResponse<byte[]> resp = (err == null) ? r : (HttpResponse<byte[]>) new StoppedResponse(req, stopped)
                String enc = decodedAs?.get()
                return enc != null ? (HttpResponse<byte[]>) new DecodedResponse(resp, enc) : resp
            }
            RuntimeException own = failure.get()
            if (own != null) throw own
            throw (err instanceof CompletionException ? err : new CompletionException(err))
        }.thenCompose { HttpResponse<byte[]> resp ->
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
            int code = resp.statusCode()
            if (ctx?.oneShotBody && (code == 307 || code == 308)) {
                throw new RedirectRejectedException(("A ${code} redirect to ${loggableUri(target)} must re-send the request body, " +
                        'but a one-shot body (InputStream or Publisher) cannot be re-sent; use a Path or byte[] body to allow it').toString())
            }
            if (config.enableLogging) {
                log.info("[GroovyHttpClient] following {} redirect {} -> {}", resp.statusCode(), loggableUri(req.uri()), loggableUri(target))
            }
            return sendFollowingRedirects(redirectRequest(req, target, resp.statusCode()), hop + 1, ctx)
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

    private HttpResponse.BodyHandler<byte[]> boundedBodyHandler(HttpRequest req,
            java.util.concurrent.atomic.AtomicReference<RuntimeException> sink = null,
            java.util.concurrent.atomic.AtomicReference<String> decodedAs = null,
            long max = maxResponseBytes,
            StreamTarget target = null,
            long idleMs = 0L,
            java.util.concurrent.atomic.AtomicReference<HttpResponse.ResponseInfo> stoppedAt = null) {
        String method = req?.method()
        URI uri = req?.uri()
        return { HttpResponse.ResponseInfo info ->
            ContentDecoder decoder = null
            if (decodedAs != null) {
                String enc = info.headers().firstValue('Content-Encoding').orElse(null)
                decoder = ContentDecoder.forEncoding(enc)
                decodedAs.set(decoder != null ? enc : null)
            }
            // Only a final 2xx body is streamed; redirect and error bodies are read into memory, bounded.
            int status = info.statusCode()
            StreamTarget.BodySink out = (target != null && status >= 200 && status < 300) ? target.openAttempt(info.headers().firstValue('Content-Type').orElse(null)) : null
            Runnable onStop = stoppedAt == null ? null : ({ -> stoppedAt.set(info) } as Runnable)
            new BoundedBodySubscriber(max, method, uri, sink, decoder, out, idleMs, onStop)
        } as HttpResponse.BodyHandler<byte[]>
    }

    @CompileStatic
    static class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long max
        private final CompletableFuture<byte[]> result = new CompletableFuture<byte[]>()
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        private Flow.Subscription subscription
        /** Bytes kept: decoded bytes when a decoder is set (the zip-bomb guard), else wire bytes. The limit applies here. */
        private long received = 0L
        private long wireBytes = 0L

        private final String method
        private final URI uri
        private final java.util.concurrent.atomic.AtomicReference<RuntimeException> sink
        private final ContentDecoder decoder
        private final ContentDecoder.Sink decodedSink
        /** Streamed requests: where kept bytes go instead of the in-memory buffer (2.1). */
        private final StreamTarget.BodySink out

        /** idleTimeout (2.1): fail when no bytes arrive for this long while the consumer is not busy. */
        private final long idleNanos
        private volatile long lastActivity = 0L
        private volatile boolean busy = false
        /** Records the response head when a consumer stops the stream (HTTP/2 and HTTP/3 report the cancel as a failure). */
        private final Runnable onStop
        private java.util.concurrent.ScheduledFuture<?> watchdog
        private static final java.util.concurrent.ScheduledExecutorService IDLE_TIMER =
                Executors.newSingleThreadScheduledExecutor({ Runnable r ->
                    Thread t = new Thread(r, 'groovy-httpclient-idle-timeout')
                    t.setDaemon(true)
                    t
                } as ThreadFactory)

        BoundedBodySubscriber(long max, String method = null, URI uri = null,
                              java.util.concurrent.atomic.AtomicReference<RuntimeException> sink = null,
                              ContentDecoder decoder = null, StreamTarget.BodySink out = null, long idleMs = 0L,
                              Runnable onStop = null) {
            this.out = out
            this.onStop = onStop
            this.idleNanos = idleMs * 1_000_000L
            this.max = max
            this.method = method
            this.uri = uri
            this.sink = sink
            this.decoder = decoder
            this.decodedSink = { byte[] b, int off, int len -> keep(b, off, len) } as ContentDecoder.Sink
        }

        @Override
        CompletionStage<byte[]> getBody() { result }

        @Override
        void onSubscribe(Flow.Subscription s) {
            this.subscription = s
            if (idleNanos > 0L) {
                lastActivity = System.nanoTime()
                long period = Math.max(10L, idleNanos.intdiv(4_000_000L) as long)
                watchdog = IDLE_TIMER.scheduleAtFixedRate({ -> checkIdle() } as Runnable, period, period, TimeUnit.MILLISECONDS)
            }
            s.request(Long.MAX_VALUE)
        }

        void checkIdle() {
            if (result.isDone()) { watchdog?.cancel(false); return }
            if (busy) return   // a slow consumer is not an idle server
            if (System.nanoTime() - lastActivity > idleNanos) {
                fail(new CompletionException(new java.net.http.HttpTimeoutException(
                        'No response bytes for ' + idleNanos.intdiv(1_000_000L) + ' ms (idleTimeout) from ' + method + ' ' + uri?.path)))
            }
        }

        @Override
        void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return
            busy = true
            try {
                for (ByteBuffer bb : items) {
                    if (decoder == null) {
                        int n = bb.remaining()
                        byte[] chunk = new byte[n]
                        bb.get(chunk)
                        keep(chunk, 0, n)
                    } else {
                        wireBytes += bb.remaining()
                        if (max != Long.MAX_VALUE && wireBytes > max) throw new ResponseTooLargeException(max, wireBytes, method, uri)
                        decoder.decode(bb, decodedSink)
                    }
                }
            } catch (StreamTarget.StopStream stop) {
                stopEarly()
            } catch (RuntimeException e) {
                fail(e)
            } catch (Exception e) {
                fail(new CompletionException(e))   // a consumer's checked exception; unwrapped for the caller
            } finally {
                lastActivity = System.nanoTime()
                busy = false
            }
        }

        /** The consumer stopped the stream: cancel the response and complete normally (2.1). */
        private void stopEarly() {
            watchdog?.cancel(false)
            onStop?.run()
            decoder?.close()
            try { out?.complete() } catch (RuntimeException ignored) { }
            result.complete(new byte[0])
            subscription?.cancel()
        }

        /** Count and keep body bytes; over the limit this throws before buffering them. */
        void keep(byte[] b, int off, int len) {
            received += len
            if (max != Long.MAX_VALUE && received > max) throw new ResponseTooLargeException(max, received, method, uri)
            if (out != null) out.write(b, off, len)
            else buffer.write(b, off, len)
        }

        private void fail(RuntimeException e) {
            if (result.isDone()) return
            watchdog?.cancel(false)
            if (sink != null) sink.set(e)
            result.completeExceptionally(e)
            decoder?.close()
            out?.abort()
            subscription?.cancel()
        }

        @Override
        void onError(Throwable t) {
            watchdog?.cancel(false)
            decoder?.close()
            out?.abort()
            result.completeExceptionally(t)
        }

        @Override
        void onComplete() {
            watchdog?.cancel(false)
            if (result.isDone()) return
            try {
                if (decoder != null) decoder.finish(decodedSink)
                if (out != null) out.complete()
            } catch (StreamTarget.StopStream stop) {
                result.complete(new byte[0])
                return
            } catch (Exception e) {
                RuntimeException re = e instanceof RuntimeException ? (RuntimeException) e : new CompletionException(e)
                out?.abort()
                if (sink != null) sink.set(re)
                result.completeExceptionally(re)
                return
            }
            result.complete(out != null ? new byte[0] : buffer.toByteArray())
        }
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

        // The query is already percent-encoded. The multi-argument URI constructors quote '%' again
        // ('&' -> %26 -> %2526), so splice the raw query into the raw URI string instead.
        String raw = uri.toString()
        int hash = raw.indexOf('#')
        String fragment = hash >= 0 ? raw.substring(hash) : ''
        String noFragment = hash >= 0 ? raw.substring(0, hash) : raw
        int q = noFragment.indexOf('?')
        String head = q >= 0 ? noFragment.substring(0, q) : noFragment
        URI.create(head + '?' + newQuery + fragment)
    }

    private static String encodeForm(Map<String, ?> params, Charset charset) {
        BuiltinCodecs.formString(params, charset)
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
        awaitResult(postMultipart(path, dsl), timeout)
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

        def pub = gzipIfRequested(wrapped, buildMultipartBody(parts, boundary))
        boolean decodeResponse = prepareCompression(wrapped)

        def reqSupplier = { ->
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri)
            wrapped.applyTo(b, config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT, effectiveHttpVersion, config.http3Discovery)
            b.POST(pub).build()
        }

        def effectiveRetry = wrapped.getRetryPolicyOrNull() ?: config.retryPolicy ?: RetryPolicy.none()
        def ctx = new RequestContext("POST", uri, reqSupplier, effectiveRetry)
        ctx.decodeResponse = decodeResponse
        ctx.maxResponseBytes = wrapped.getMaxResponseBytesOrNull() ?: maxResponseBytes
        ctx.streamTarget = streamTargetFor(wrapped)
        ctx.idleTimeoutMs = wrapped.getIdleTimeoutOrNull()?.toMillis() ?: 0L

        def chain = buildInterceptorChain()

        def outcome = executeWithCircuitBreaker {
            // >= 400 raised after retry; the breaker counts only transient errors and 5xx
            attemptWithRetry(ctx, chain).thenApply { HttpClientResponse r -> raiseForStatus(r, ctx) }
        }
        ctx.streamTarget != null ? finishStream(outcome, ctx.streamTarget) : outcome
    }

    HttpClientResponse postMultipartSync(
            String path,
            List<MultipartPart> parts,
            Closure cfg = null,
            Duration timeout = DEFAULT_SYNC_TIMEOUT
    ) {
        awaitResult(postMultipart(path, parts, cfg), timeout)
    }

    CompletableFuture<byte[]> downloadBytes(String path,
                                            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure cfg = null) {

        // Reuse the normal request pipeline (cookies, interceptors, circuit breaker, retries)
        return get(path, cfg).thenApply { HttpClientResponse resp ->
            resp.bytes
        }
    }

    byte[] downloadBytesSync(String path, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        awaitResult(downloadBytes(path), timeout)
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
        /** Decode a gzip / deflate response body (compression on and Accept-Encoding sent by the client). */
        boolean decodeResponse = false
        /** Per-request maxResponseBytes (2.1); null = the client's. */
        Long maxResponseBytes = null
        /** Streamed requests: where a 2xx body goes (2.1). */
        StreamTarget streamTarget = null
        /** Per-request idleTimeout in ms (2.1); 0 = off. */
        long idleTimeoutMs = 0L
        /** The body is an InputStream / Publisher that can be sent once only: no retry, no 307/308 (2.1). */
        boolean oneShotBody = false

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

    /**
     * A response with status >= 400 (raised after retries), or a client-side limit ({@link ResponseTooLargeException}).
     *
     * The message holds no response body unless {@code SecurityConfig.errorBodyPreviewChars} opts in (SEC-11, 2.1):
     * <pre>HTTP 404 Not Found (GET https://api.example.com/orders/42, 1284 bytes, application/problem+json)</pre>
     * The URI there is {@code loggableUri}: no query string, no userinfo. Everything else is on fields:
     * headers, body, bytes, the full URI, {@code bodyAs(Type)}, {@code json} and RFC 9457 {@code problem}.
     */
    static class HttpResponseException extends RuntimeException {
        private static final long serialVersionUID = 2L
        /** Upper bound for SecurityConfig.errorBodyPreviewChars. */
        static final int MAX_PREVIEW_CHARS = 4096

        final int statusCode
        /** Standard RFC 9110 phrase for statusCode ('' for a code with none); never the server's own text. */
        final String reasonPhrase
        /** Request method, or null when unknown. */
        final String method
        /** The full request URI (query included) -- log it with care; the message uses loggableUri. */
        final URI uri
        /** Response headers, case-insensitive, unmodifiable. */
        final Map<String, List<String>> responseHeaders
        /** Response Content-Type, or null. */
        final MediaType contentType
        /** The full response body as text. Kept out of the message (SEC-11). */
        final String responseBody
        private final byte[] bytes
        private final transient CodecRegistry codecs
        private transient volatile boolean problemTried
        private transient volatile ProblemDetail problemCache

        /** @deprecated since 2.1: the client builds these from the response; kept for code that constructs them. */
        @Deprecated
        HttpResponseException(int statusCode, String responseBody) {
            this(statusCode, null, null, null, null,
                    responseBody == null ? new byte[0] : responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    responseBody, CodecRegistry.defaults(), 0, null)
        }

        HttpResponseException(HttpClientResponse r, String method, URI uri, int previewChars = 0) {
            this(r.statusCode, method, uri, r.headers, r.contentType, r.bytes, r.body, r.codecs, previewChars, null)
            this.response = r
            r.attachException(this)
        }

        private transient HttpClientResponse response

        /** The response this was raised for (null for client-side limits and after deserialisation). */
        HttpClientResponse getResponse() { response }

        protected HttpResponseException(int statusCode, String method, URI uri, Map<String, List<String>> headers,
                                        String contentTypeHeader, byte[] bytes, String body, CodecRegistry codecs,
                                        int previewChars, String message) {
            super(message ?: describe(statusCode, method, uri, bytes == null ? 0L : (long) bytes.length,
                    contentTypeHeader, body, previewChars))
            this.statusCode = statusCode
            this.reasonPhrase = ReasonPhrases.of(statusCode)
            this.method = method
            this.uri = uri
            this.responseHeaders = copyHeaders(headers)
            this.contentType = MediaType.parseOrNull(contentTypeHeader)
            this.responseBody = body
            this.bytes = bytes ?: new byte[0]
            this.codecs = codecs
        }

        /** A copy of the raw response bytes. */
        byte[] getResponseBytes() { bytes.clone() }

        /** First value of a response header, case-insensitive. */
        String header(String name) { responseHeaders.get(name)?.getAt(0) }

        /** The body parsed for its Content-Type by the client's codec registry. */
        Object getParsed() { requireCodecs().parse(bytes, contentType, Object) }

        /** The body as JSON, whatever the Content-Type says. */
        Object getJson() {
            def t = MediaType.parse('application/json')
            requireCodecs().parse(bytes, contentType?.charset ? t.withCharset(contentType.charset) : t, Object)
        }

        /** The body as an instance of type (Jackson when present, else Groovy coercion). */
        def <T> T bodyAs(Class<T> type) { (T) requireCodecs().parse(bytes, contentType, type) }

        /** RFC 9457 problem details when the Content-Type is application/problem+json and it parses; else null. */
        ProblemDetail getProblem() {
            if (!problemTried) {
                problemCache = null
                if (contentType?.essence == 'application/problem+json') {
                    try {
                        def parsed = requireCodecs().parse(bytes, contentType, Object)
                        if (parsed instanceof Map) problemCache = ProblemDetail.from((Map) parsed)
                    } catch (RuntimeException ignored) {
                        problemCache = null
                    }
                }
                problemTried = true
            }
            problemCache
        }

        private CodecRegistry requireCodecs() {
            if (codecs == null) {
                throw new IllegalStateException('No codec registry on this exception (it was deserialised); use responseBody / responseBytes')
            }
            codecs
        }

        private static Map<String, List<String>> copyHeaders(Map<String, List<String>> h) {
            def m = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
            h?.each { k, v -> if (k != null) m.put(k.toString(), Collections.unmodifiableList(new ArrayList<String>(v ?: []))) }
            Collections.unmodifiableMap(m)
        }

        static String describe(int status, String method, URI uri, long size, String contentTypeHeader, String body,
                               int previewChars) {
            List<String> parts = []
            if (uri != null) parts << (method ? method + ' ' : '') + loggableUri(uri)
            parts << size + ' bytes'
            String essence = MediaType.parseOrNull(contentTypeHeader)?.essence
            if (essence) parts << essence
            String reason = ReasonPhrases.of(status)
            StringBuilder sb = new StringBuilder('HTTP ').append(status)
            if (reason) sb.append(' ').append(reason)
            sb.append(' (').append(parts.join(', ')).append(')')
            if (previewChars > 0 && body) {
                String flat = body.replaceAll(/\p{Cntrl}+/, ' ').trim()
                sb.append(': ').append(flat.length() > previewChars ? flat.substring(0, previewChars) + '...' : flat)
            }
            sb.toString()
        }
    }

    /**
     * The response was larger than maxResponseBytes (a client-side limit, not the server's 413): reading stopped
     * once the limit was crossed. statusCode is 413 so 2.0 checks keep working; catch this type to tell them apart.
     */
    static class ResponseTooLargeException extends HttpResponseException {
        private static final long serialVersionUID = 1L
        /** The maxResponseBytes in force. */
        final long limit
        /** Bytes read when the limit was crossed (at least limit + 1). */
        final long bytesRead

        ResponseTooLargeException(long limit, long bytesRead, String method = null, URI uri = null) {
            super(413, method, uri, null, null, new byte[0], '', CodecRegistry.defaults(), 0, tooLarge(limit, method, uri))
            this.limit = limit
            this.bytesRead = bytesRead
        }

        private static String tooLarge(long limit, String method, URI uri) {
            'Response exceeded maxResponseBytes (' + limit + ')' + (uri != null ? ' (' + (method ? method + ' ' : '') + loggableUri(uri) + ')' : '')
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
        ContentParseException(String message, Throwable cause) { super(message, cause) }
    }

    /** A redirect was refused by policy: host not allowed, HTTPS to HTTP, bad scheme, or too many hops. */
    static class RedirectRejectedException extends RuntimeException {
        RedirectRejectedException(String message) { super(message) }
    }
}