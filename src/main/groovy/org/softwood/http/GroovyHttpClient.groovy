package org.softwood.http

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.HttpCookie
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * GroovyHttpClient — virtual-thread HTTP client with circuit breaker, cookies, and SecurityConfig integration.
 *
 * This version preserves all previous capabilities while adding a new constructor that accepts SecurityConfig
 * to enable secure-by-default behavior (hostname verification, cookie policy, absolute URL/host allow-list, etc.).
 */
class GroovyHttpClient implements AutoCloseable {
    private final URI host
    private final HttpClient httpClient
    private final CircuitBreaker circuitBreaker
    private final CookieManager cookieManager

    // --- Security/behavior flags (populated by SecurityConfig constructor; benign defaults otherwise) ---
    private boolean allowAbsoluteUrls = true
    private Set<String> allowedHosts = Collections.synchronizedSet(new LinkedHashSet<>())
    private long maxResponseBytes = Long.MAX_VALUE
    private boolean enableLogging = false

    // Default headers are Map<String, List<String>>
    private Map<String, List<String>> defaultHeaders = [:].withDefault { [] }.asSynchronized()

    // Thread counter for naming virtual threads
    private static final AtomicInteger threadCounter = new AtomicInteger(0)

    // Default timeout values
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10)
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30)

    // Default synchronous method timeout
    public static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(30)

    // Circuit breaker defaults
    public static final int DEFAULT_FAILURE_THRESHOLD = 5
    public static final long DEFAULT_RESET_TIMEOUT_MS = 30000

    // ----------------------------------------------------------------------------------------------
    // Constructors (backward compatible)
    // ----------------------------------------------------------------------------------------------

    GroovyHttpClient(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_FAILURE_THRESHOLD, DEFAULT_RESET_TIMEOUT_MS, null, null)
    }

    /**
     * Generic constructor for variable args that then calls the full constructor.
     */
    GroovyHttpClient(Object... args) {
        this(*resolveConstructorArgs(args))
    }

    // Helper method to compute argument list for delegation
    private static Object[] resolveConstructorArgs(Object... args) {
        if (!args) {
            throw new IllegalArgumentException("Base URL is required")
        }

        String baseUrl = args[0] as String
        if (!baseUrl || !baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Invalid base URL: $baseUrl")
        }

        // Defaults
        Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT
        Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT
        Integer failureThreshold = DEFAULT_FAILURE_THRESHOLD
        Long resetTimeoutMs = DEFAULT_RESET_TIMEOUT_MS
        SSLContext sslContext = null
        ThreadFactory threadFactory = null

        switch (args.length) {
            case 2:
                connectTimeout = args[1] as Duration
                break
            case 3:
                connectTimeout = args[1] as Duration
                requestTimeout = args[2] as Duration
                break
            case 4:
                connectTimeout   = args[1] as Duration
                requestTimeout   = args[2] as Duration
                failureThreshold = args[3] as Integer
                break
            case 5:
                connectTimeout   = args[1] as Duration
                requestTimeout   = args[2] as Duration
                failureThreshold = args[3] as Integer
                resetTimeoutMs   = (args[4] as Number).longValue()
                break
            case 6:
                connectTimeout   = args[1] as Duration
                requestTimeout   = args[2] as Duration
                failureThreshold = args[3] as Integer
                resetTimeoutMs   = (args[4] as Number).longValue()
                sslContext       = args[5] as SSLContext
                break
            case 7:
                connectTimeout   = args[1] as Duration
                requestTimeout   = args[2] as Duration
                failureThreshold = args[3] as Integer
                resetTimeoutMs   = (args[4] as Number).longValue()
                sslContext       = args[5] as SSLContext
                threadFactory    = args[6] as ThreadFactory
                break
        }

        // Return arguments for the main 7-arg constructor
        return [baseUrl, connectTimeout, requestTimeout,
                failureThreshold, resetTimeoutMs, sslContext, threadFactory] as Object[]
    }

    GroovyHttpClient(String baseUrl,
                     Duration connectTimeout,
                     Duration requestTimeout,
                     Integer failureThreshold,
                     Long resetTimeoutMs,
                     SSLContext sslContext,
                     ThreadFactory threadFactory) {

        if (!baseUrl || !baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Invalid base URL: $baseUrl")
        }

        this.host = URI.create(baseUrl)
        this.circuitBreaker = new CircuitBreaker(failureThreshold, resetTimeoutMs)

        // Initialize cookie manager (legacy default remains ACCEPT_ALL for backward compatibility)
        this.cookieManager = new CookieManager()
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)

        def tf = threadFactory ?: Thread.ofVirtual().name("http-client-", threadCounter.getAndIncrement()).factory()

        if (sslContext) {
            this.httpClient = HttpClient.newBuilder()
                    .executor(Executors.newThreadPerTaskExecutor(tf))
                    .connectTimeout(connectTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .sslContext(sslContext)
                    .cookieHandler(cookieManager)
                    .build()
        } else {
            // Legacy behavior: create an insecure client if sslContext is missing
            this.httpClient = createInsecureHttpClient(tf, connectTimeout, cookieManager)
        }

        // Legacy: allowAbsoluteUrls true, allowedHosts includes base
        this.allowedHosts.add(this.host.host)
    }

    /**
     * NEW: Secure-by-default constructor using SecurityConfig.
     */
    GroovyHttpClient(SecurityConfig config) {
        if (!config?.baseUrl || !config.baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Invalid base URL in SecurityConfig: ${config?.baseUrl}")
        }

        this.host = URI.create(config.baseUrl)
        this.circuitBreaker = new CircuitBreaker(config.failureThreshold ?: DEFAULT_FAILURE_THRESHOLD,
                config.resetTimeoutMs ?: DEFAULT_RESET_TIMEOUT_MS)

        // Cookie manager with policy from config
        this.cookieManager = new CookieManager()
        this.cookieManager.setCookiePolicy(config.cookiePolicy ?: CookiePolicy.ACCEPT_ORIGINAL_SERVER)

        // Save security behavior flags
        this.allowAbsoluteUrls = (config.allowAbsoluteUrls != null) ? config.allowAbsoluteUrls : false
        this.allowedHosts.clear(); this.allowedHosts.addAll(config.allowedHosts ?: [this.host.host])
        this.maxResponseBytes = (config.maxResponseBytes ?: 10_000_000L)
        this.enableLogging = (config.enableLogging ?: false)

        // Build secure HttpClient (do NOT force insecure client; use default unless explicitly permitted)
        def tf = Thread.ofVirtual().name("http-client-", threadCounter.getAndIncrement()).factory()
        def builder = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(tf))
                .connectTimeout(config.connectTimeout ?: DEFAULT_CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_2)
                .cookieHandler(cookieManager)
                .proxy(ProxySelector.getDefault())

        if (config.insecureAllowed) {
            // Honor insecureAllowed and visibly warn
            logWarn("[GroovyHttpClient] Insecure TLS permitted by SecurityConfig (trust-all). TEST/STAGING USE ONLY.")
            // Keep behavior contained by creating a permissive client
            def insecureClient = createInsecureClientForBuilder(builder)
            this.httpClient = insecureClient
        } else {
            // Secure path: rely on system/default SSLContext and enable hostname verification
            def params = new SSLParameters()
            params.setEndpointIdentificationAlgorithm("HTTPS")
            builder.sslParameters(params)
            this.httpClient = builder.build()
        }

        // Ensure base host is in the allow-list
        this.allowedHosts.add(this.host.host)
    }

    // ----------------------------------------------------------------------------------------------
    // Groovy-friendly wrapper around Java's HttpRequest.Builder.
    // ----------------------------------------------------------------------------------------------

    private class GroovyRequestBuilder {
        private final HttpRequest.Builder builder
        private final Map<String, List<String>> headers = [:].withDefault { [] }

        GroovyRequestBuilder(HttpRequest.Builder builder) {
            this.builder = builder
        }

        // Add a header (multiple values allowed)
        void header(String name, String value) {
            builder.header(name, value)
            headers[name] << value
        }

        boolean hasHeader(String name) {
            headers.containsKey(name)
        }

        GroovyRequestBuilder timeout(Duration duration) {
            builder.timeout(duration)
            return this
        }

        /** Add a cookie to this specific request */
        void cookie(String name, String value) {
            header("Cookie", "${name}=${value}")
        }

        /** Add multiple cookies to this request */
        void cookies(Map<String, String> cookies) {
            def cookieString = cookies.collect { k, v -> "${k}=${v}" }.join("; ")
            header("Cookie", cookieString)
        }

        def methodMissing(String name, args) {
            builder."$name"(*args)
        }

        HttpRequest.Builder unwrap() {
            return builder
        }

        Map<String, List<String>> getHeaders() {
            headers.collectEntries { k, v -> [(k): v.toList()] }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Response wrapper that includes body, headers, and status code
    // ----------------------------------------------------------------------------------------------

    static class HttpClientResponse {
        final String body
        final Map<String, List<String>> headers
        final int statusCode

        HttpClientResponse(HttpResponse<String> response) {
            this.body = response.body()
            this.headers = response.headers().map()
            this.statusCode = response.statusCode()
        }

        String getHeader(String name) {
            def entry = headers.find { k, v -> k.equalsIgnoreCase(name) }
            return entry?.value?.get(0)
        }

        List<String> getHeaders(String name) {
            headers.findAll { k, v -> k.equalsIgnoreCase(name) }
                    .values()
                    .flatten()  as List<String>
        }

        boolean hasHeader(String name) {
            return headers.any { k, v -> k.equalsIgnoreCase(name) }
        }

        @Override
        String toString() { body }
    }

    // ----------------------------------------------------------------------------------------------
    // Cookie Management API
    // ----------------------------------------------------------------------------------------------

    List<HttpCookie> getCookies() {
        cookieManager.cookieStore.get(host).collect()
    }

    HttpCookie getCookie(String name) {
        cookieManager.cookieStore.get(host).find { it.name == name }
    }

    GroovyHttpClient addCookie(String name, String value, String path = "/") {
        def cookie = new HttpCookie(name, value)
        cookie.path = path
        cookie.domain = host.host
        cookieManager.cookieStore.add(host, cookie)
        return this
    }

    GroovyHttpClient addCookie(HttpCookie cookie) {
        cookieManager.cookieStore.add(host, cookie)
        return this
    }

    GroovyHttpClient removeCookie(String name) {
        def cookies = cookieManager.cookieStore.get(host)
        cookies.findAll { it.name == name }.each { cookie ->
            cookieManager.cookieStore.remove(host, cookie)
        }
        return this
    }

    GroovyHttpClient clearCookies() {
        cookieManager.cookieStore.removeAll()
        return this
    }

    GroovyHttpClient setCookiePolicy(CookiePolicy policy) {
        cookieManager.setCookiePolicy(policy)
        return this
    }

    CookiePolicy getCookiePolicy() { cookieManager.cookiePolicy }

    CookieStore getCookieStore() { cookieManager.cookieStore }

    // ----------------------------------------------------------------------------------------------
    // Default Header Management API
    // ----------------------------------------------------------------------------------------------

    GroovyHttpClient withHeader(String name, String value) {
        defaultHeaders.computeIfAbsent(name) { [] } << value
        return this
    }

    GroovyHttpClient setHeader(String name, String value) {
        defaultHeaders[name] = [value]
        return this
    }

    GroovyHttpClient withHeaders(Map<String, String> headers) {
        headers.each { k, v -> defaultHeaders.computeIfAbsent(k) { [] } << v }
        return this
    }

    GroovyHttpClient setHeaders(Map<String, String> headers) {
        defaultHeaders.clear()
        headers.each { k, v -> defaultHeaders[k] = [v] }
        return this
    }

    GroovyHttpClient clearHeaders() { defaultHeaders.clear(); this }

    Map<String, List<String>> getDefaultHeaders() {
        defaultHeaders.collectEntries { k, v -> [(k): v.toList()] }
    }

    // ----------------------------------------------------------------------------------------------
    // HTTP Client Creation (legacy test helper)
    // ----------------------------------------------------------------------------------------------

    static HttpClient createInsecureHttpClient(ThreadFactory threadFactory, Duration connectTimeout, CookieManager cookieManager = null) {
        // Create a trust manager that trusts all certificates
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    X509Certificate[] getAcceptedIssuers() { return null }
                    void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, new SecureRandom())

            SSLParameters sslParameters = new SSLParameters()
            sslParameters.setEndpointIdentificationAlgorithm(null) // disable hostname verification
            sslParameters.setApplicationProtocols(["h2", "http/1.1"] as String[])

            def builder = HttpClient.newBuilder()
                    .executor(Executors.newThreadPerTaskExecutor(threadFactory))
                    .connectTimeout(connectTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)

            if (cookieManager) { builder.cookieHandler(cookieManager) }
            return builder.build()
        } catch (Exception e) {
            throw new RuntimeException("Could not create insecure HTTP client", e)
        }
    }

    /** Helper to create an insecure client using an existing builder skeleton. */
    private static HttpClient createInsecureClientForBuilder(HttpClient.Builder baseBuilder) {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    X509Certificate[] getAcceptedIssuers() { return null }
                    void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        }
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, new SecureRandom())
            SSLParameters sslParameters = new SSLParameters()
            sslParameters.setEndpointIdentificationAlgorithm(null)
            sslParameters.setApplicationProtocols(["h2", "http/1.1"] as String[])
            baseBuilder.sslContext(sslContext).sslParameters(sslParameters)
            return baseBuilder.build()
        } catch (Exception e) {
            throw new RuntimeException("Could not create insecure HTTP client", e)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Core Request Handler
    // ----------------------------------------------------------------------------------------------

    private CompletableFuture<HttpClientResponse> sendRequest(
            String method,
            String path,
            String body = null,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        // Build the base request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(resolveUri(path))
                .timeout(DEFAULT_REQUEST_TIMEOUT)

        // Set HTTP method and body
        switch (method.toUpperCase()) {
            case "GET":
                requestBuilder.GET(); break
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "PATCH":
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "DELETE":
                requestBuilder.DELETE(); break
            case "HEAD":
                requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()); break
            case "OPTIONS":
                requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody()); break
            default:
                requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
        }

        // Wrap builder for Groovy closure DSL
        def wrappedRequestBuilder = new GroovyRequestBuilder(requestBuilder)

        // Apply default headers
        defaultHeaders.each { name, values -> values.each { v -> wrappedRequestBuilder.header(name, v) } }

        // Apply request-specific closure
        if (configClosure) {
            if (configClosure.maximumNumberOfParameters == 1) {
                configClosure.call(wrappedRequestBuilder)
            } else {
                configClosure.delegate = wrappedRequestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_ONLY
                configClosure.call()
            }
        }

        // Build the final request
        HttpRequest request = wrappedRequestBuilder.unwrap().build()

        // Execute with circuit breaker
        return executeWithCircuitBreaker {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply { response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        if (maxResponseBytes != Long.MAX_VALUE && response.body() != null) {
                            // Note: this checks characters, not raw bytes; good enough as a guardrail
                            if (response.body().length() > maxResponseBytes) {
                                circuitBreaker.recordFailure()
                                throw new HttpResponseException(413, "Body too large")
                            }
                        }
                        return new HttpClientResponse(response)
                    }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Asynchronous HTTP Methods
    // ----------------------------------------------------------------------------------------------

    CompletableFuture<HttpClientResponse> get(String path,
                                              @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("GET", path, null, configClosure)
    }

    CompletableFuture<HttpClientResponse> post(String path, String body,
                                               @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("POST", path, body, configClosure)
    }

    CompletableFuture<HttpClientResponse> put(String path, String body,
                                              @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("PUT", path, body, configClosure)
    }

    CompletableFuture<HttpClientResponse> patch(String path, String body,
                                                @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("PATCH", path, body, configClosure)
    }

    CompletableFuture<HttpClientResponse> delete(String path,
                                                 @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("DELETE", path, null, configClosure)
    }

    CompletableFuture<HttpClientResponse> head(String path,
                                               @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("HEAD", path, null, configClosure)
    }

    CompletableFuture<HttpClientResponse> options(String path,
                                                  @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null) {
        sendRequest("OPTIONS", path, null, configClosure)
    }

    // ----------------------------------------------------------------------------------------------
    // Synchronous HTTP Methods
    // ----------------------------------------------------------------------------------------------

    HttpClientResponse getSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return get(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse postSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return post(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse putSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return put(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse deleteSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return delete(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse patchSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return patch(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse headSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return head(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse optionsSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return options(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // ----------------------------------------------------------------------------------------------
    // Helper Methods
    // ----------------------------------------------------------------------------------------------

    /** Resolves a path against the base URL with optional SSRF protections. */
    private URI resolveUri(String path) {
        if (!path) { return host }

        // Absolute URL handling (SSRF guard if disabled)
        if (path.toLowerCase().startsWith('http://') || path.toLowerCase().startsWith('https://')) {
            if (!allowAbsoluteUrls) {
                throw new IllegalArgumentException("Absolute URLs disabled by configuration")
            }
            try {
                def uri = new URI(path)
                if (!allowedHosts.isEmpty() && !allowedHosts.contains(uri.host)) {
                    throw new IllegalArgumentException("Target host not allowed: ${uri.host}")
                }
                return uri
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid absolute URL provided as path: $path", e)
            }
        }

        String hostStr = host.toString().endsWith('/') ? host.toString()[0..-2] : host.toString()
        String pathStr = path.startsWith('/') ? path : "/$path"
        return URI.create(hostStr + pathStr)
    }

    /** Executes an HTTP operation with circuit breaker protection */
    private <T> CompletableFuture<T> executeWithCircuitBreaker(Supplier<CompletableFuture<T>> supplier) {
        if (circuitBreaker.isOpen()) {
            CompletableFuture<T> future = new CompletableFuture<>()
            future.completeExceptionally(new CircuitOpenException("Circuit is open"))
            return future
        }

        try {
            CompletableFuture<T> future = supplier.get()
            return future.handle((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = throwable
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause()
                    }

                    // Record failure for any exception
                    circuitBreaker.recordFailure()

                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause
                    } else {
                        throw new CompletionException(cause)
                    }
                }
                return result
            })
        } catch (Exception e) {
            circuitBreaker.recordFailure()
            CompletableFuture<T> future = new CompletableFuture<>()
            future.completeExceptionally(e)
            return future
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------------

    @Override
    void close() { /* HttpClient has nothing to close; hook reserved for future */ }

    String toString(path='/') { resolveUri(path).toString() }

    // ----------------------------------------------------------------------------------------------
    // Inner Classes
    // ----------------------------------------------------------------------------------------------

    private static class CircuitBreaker {
        private final int failureThreshold
        private final long resetTimeoutMs
        private final AtomicInteger failureCount = new AtomicInteger(0)
        private volatile long openStateTime = 0

        CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
            this.failureThreshold = failureThreshold
            this.resetTimeoutMs = resetTimeoutMs
        }

        boolean isOpen() {
            if (openStateTime > 0) {
                if (System.currentTimeMillis() - openStateTime > resetTimeoutMs) {
                    reset()
                    return false
                }
                return true
            }
            return failureCount.get() >= failureThreshold
        }

        void recordFailure() {
            int currentFailures = failureCount.incrementAndGet()
            if (currentFailures >= failureThreshold) {
                openStateTime = System.currentTimeMillis()
            }
        }

        void reset() { failureCount.set(0); openStateTime = 0 }
    }

    static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String message) { super(message) }
    }

    static class HttpResponseException extends RuntimeException {
        final int statusCode
        HttpResponseException(int statusCode, String responseBody) {
            super("HTTP error: $statusCode, body: $responseBody")
            this.statusCode = statusCode
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Logging helpers (minimal; plug into your logger if needed)
    // ----------------------------------------------------------------------------------------------

    private void logWarn(String msg) { if (enableLogging) println "WARN  $msg" }
    @SuppressWarnings('unused')
    private void logInfo(String msg) { if (enableLogging) println "INFO  $msg" }
}
