package org.softwood.http

import groovy.util.logging.Slf4j

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
 * GroovyHttpClient — virtual-thread HTTP client with:
 *  - Circuit breaker
 *  - Cookie management
 *  - SecurityConfig integration
 *  - Multipart upload & file download
 *  - Groovy DSL for request configuration
 */
@Slf4j
class GroovyHttpClient implements AutoCloseable {

    // -------------------------------------------------------------------------
    // Static defaults
    // -------------------------------------------------------------------------

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10)
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30)
    public static final Duration DEFAULT_SYNC_TIMEOUT    = Duration.ofSeconds(30)

    public static final int  DEFAULT_FAILURE_THRESHOLD = 5
    public static final long DEFAULT_RESET_TIMEOUT_MS  = 30_000L

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0)

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    final SecurityConfig config
    private final URI host
    private final HttpClient httpClient
    private final CookieManager cookieManager
    private final CircuitBreaker circuitBreaker

    private final boolean allowAbsoluteUrls
    private final Set<String> allowedHosts
    private final long maxResponseBytes

    // Default headers: Map<String, List<String>>
    private final Map<String, List<String>> defaultHeaders =
            [:].withDefault { [] }.asSynchronized()

    /**
     * Simple cookie handler facade used by tests:
     *
     *   client.cookieHandler.addCookie("session","abc123")
     *
     * These cookies will be sent on all requests as a single "Cookie" header,
     * unless an explicit Cookie header is already set.
     */
    final SimpleCookieHandler cookieHandler = new SimpleCookieHandler()

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Main constructor — configure via SecurityConfig.
     */
    GroovyHttpClient(SecurityConfig config) {
        if (!config?.baseUrl || !config.baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Invalid base URL in SecurityConfig: ${config?.baseUrl}")
        }

        this.config = config
        this.host = URI.create(config.baseUrl)

        this.cookieManager = new CookieManager(null, config.cookiePolicy ?: CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        this.circuitBreaker = new CircuitBreaker(
                config.failureThreshold ?: DEFAULT_FAILURE_THRESHOLD,
                config.resetTimeoutMs   ?: DEFAULT_RESET_TIMEOUT_MS
        )

        this.allowAbsoluteUrls = config.allowAbsoluteUrls
        this.allowedHosts = Collections.synchronizedSet(new LinkedHashSet<>(config.allowedHosts ?: [host.host]))
        this.maxResponseBytes = config.maxResponseBytes ?: 10_000_000L

        ThreadFactory tf = Thread.ofVirtual()
                .name("http-client-", THREAD_COUNTER.getAndIncrement())
                .factory()

        def builder = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(tf))
                .connectTimeout(config.connectTimeout ?: DEFAULT_CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(config.allowRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .cookieHandler(cookieManager)
                .proxy(ProxySelector.getDefault())

        if (config.insecureAllowed) {
            this.httpClient = createInsecureClient(builder, config)
            log.warn("[GroovyHttpClient] Insecure TLS permitted by SecurityConfig (trustAll/testing profile). TEST/DEV USE ONLY.")
        } else {
            this.httpClient = createSecureClient(builder, config)
        }

        this.allowedHosts.add(this.host.host)
    }

    /**
     * Convenience constructor for tests:
     *
     *   new GroovyHttpClient("http://localhost:8080")
     *
     * This uses SecurityConfig.testing(baseUrl).
     */
    GroovyHttpClient(String baseUrl) {
        this(SecurityConfig.testing(baseUrl))
    }

    // -------------------------------------------------------------------------
    // HttpClient building
    // -------------------------------------------------------------------------

    private static HttpClient createSecureClient(HttpClient.Builder baseBuilder, SecurityConfig config) {
        SSLParameters sslParams = new SSLParameters()

        if (config.allowedTlsProtocols) {
            sslParams.setProtocols(config.allowedTlsProtocols as String[])
        }

        if (config.allowedCipherSuites) {
            sslParams.setCipherSuites(config.allowedCipherSuites as String[])
        }

        sslParams.setEndpointIdentificationAlgorithm(
                config.enforceHostnameVerification ? "HTTPS" : null
        )

        baseBuilder.sslParameters(sslParams)
        baseBuilder.sslContext(SSLContext.getDefault())
        return baseBuilder.build()
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
        sslParams.setEndpointIdentificationAlgorithm(
                config.enforceHostnameVerification ? "HTTPS" : null
        )
        sslParams.setApplicationProtocols(["h2", "http/1.1"] as String[])

        if (config.allowedTlsProtocols) {
            sslParams.setProtocols(config.allowedTlsProtocols as String[])
        }

        if (config.allowedCipherSuites) {
            sslParams.setCipherSuites(config.allowedCipherSuites as String[])
        }

        baseBuilder.sslContext(sslContext)
                .sslParameters(sslParams)

        return baseBuilder.build()
    }

    // -------------------------------------------------------------------------
    // Simple cookie handler facade
    // -------------------------------------------------------------------------

    static class SimpleCookieHandler {
        private final Map<String, String> cookies = [:]

        void addCookie(String name, String value) {
            cookies[name] = value
        }

        void removeCookie(String name) {
            cookies.remove(name)
        }

        void clear() {
            cookies.clear()
        }

        Map<String, String> getCookies() {
            Collections.unmodifiableMap(cookies)
        }

        /**
         * Render as Cookie header: "a=1; b=2"
         */
        String asHeaderValue() {
            cookies.collect { k, v -> "${k}=${v}" }.join("; ")
        }

        boolean isEmpty() {
            cookies.isEmpty()
        }
    }

    // -------------------------------------------------------------------------
    // Groovy-friendly Request Builder wrapper
    // -------------------------------------------------------------------------

    private class GroovyRequestBuilder {
        private final HttpRequest.Builder builder
        private final Map<String, List<String>> headers = [:].withDefault { [] }
        private final List<MultipartPart> multipartParts = []

        GroovyRequestBuilder(HttpRequest.Builder builder) {
            this.builder = builder
        }

        void header(String name, String value) {
            builder.header(name, value)
            headers[name] << value
        }

        boolean hasHeader(String name) {
            headers.keySet().any { it.equalsIgnoreCase(name) }
        }

        GroovyRequestBuilder timeout(Duration duration) {
            builder.timeout(duration)
            return this
        }

        void cookie(String name, String value) {
            header("Cookie", "${name}=${value}")
        }

        void cookies(Map<String, String> cookies) {
            def cookieString = cookies.collect { k, v -> "${k}=${v}" }.join("; ")
            header("Cookie", cookieString)
        }

        void multipart(List<MultipartPart> parts) {
            if (parts) multipartParts.addAll(parts)
        }

        void part(MultipartPart part) {
            if (part) multipartParts << part
        }

        List<MultipartPart> getMultipartParts() {
            Collections.unmodifiableList(multipartParts)
        }

        def methodMissing(String name, args) {
            builder."$name"(*args)
        }

        HttpRequest.Builder unwrap() {
            builder
        }

        Map<String, List<String>> getHeaders() {
            headers.collectEntries { k, v -> [(k): v.toList()] }
        }
    }

    // -------------------------------------------------------------------------
    // Response wrapper
    // -------------------------------------------------------------------------

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
            entry?.value?.get(0)
        }

        List<String> getHeaders(String name) {
            headers.findAll { k, v -> k.equalsIgnoreCase(name) }
                    .values()
                    .flatten() as List<String>
        }

        boolean hasHeader(String name) {
            headers.any { k, v -> k.equalsIgnoreCase(name) }
        }

        String toString() { body }
    }

    // -------------------------------------------------------------------------
    // Cookie management API (backed by CookieManager)
    // -------------------------------------------------------------------------

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
        this
    }

    GroovyHttpClient addCookie(HttpCookie cookie) {
        cookieManager.cookieStore.add(host, cookie)
        this
    }

    GroovyHttpClient removeCookie(String name) {
        def cookies = cookieManager.cookieStore.get(host)
        cookies.findAll { it.name == name }.each { c ->
            cookieManager.cookieStore.remove(host, c)
        }
        this
    }

    GroovyHttpClient clearCookies() {
        cookieManager.cookieStore.removeAll()
        this
    }

    GroovyHttpClient setCookiePolicy(CookiePolicy policy) {
        cookieManager.cookiePolicy = policy
        this
    }

    CookiePolicy getCookiePolicy() {
        cookieManager.cookiePolicy
    }

    CookieStore getCookieStore() {
        cookieManager.cookieStore
    }

    // -------------------------------------------------------------------------
    // Default header API
    // -------------------------------------------------------------------------

    GroovyHttpClient withHeader(String name, String value) {
        defaultHeaders.computeIfAbsent(name) { [] } << value
        this
    }

    GroovyHttpClient setHeader(String name, String value) {
        defaultHeaders[name] = [value]
        this
    }

    GroovyHttpClient withHeaders(Map<String, String> headers) {
        headers.each { k, v ->
            defaultHeaders.computeIfAbsent(k) { [] } << v
        }
        this
    }

    GroovyHttpClient setHeaders(Map<String, String> headers) {
        defaultHeaders.clear()
        headers.each { k, v -> defaultHeaders[k] = [v] }
        this
    }

    GroovyHttpClient clearHeaders() {
        defaultHeaders.clear()
        this
    }

    Map<String, List<String>> getDefaultHeaders() {
        defaultHeaders.collectEntries { k, v -> [(k): v.toList()] }
    }

    // -------------------------------------------------------------------------
    // Core request sending logic (string body)
    // -------------------------------------------------------------------------

    private CompletableFuture<HttpClientResponse> sendRequest(
            String method,
            String path,
            String body = null,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        def uri = resolveUri(path)
        log.debug("Preparing {} request to {}", method, uri)

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)

        switch (method.toUpperCase()) {
            case "GET":     requestBuilder.GET(); break
            case "POST":    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "PUT":     requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "PATCH":   requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "")); break
            case "DELETE":  requestBuilder.DELETE(); break
            case "HEAD":    requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()); break
            case "OPTIONS": requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody()); break
            default:
                requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
        }

        def wrapped = new GroovyRequestBuilder(requestBuilder)

        // default headers
        defaultHeaders.each { name, values ->
            values.each { v -> wrapped.header(name, v) }
        }

        // request-specific DSL
        if (configClosure) {
            if (configClosure.maximumNumberOfParameters == 1) {
                configClosure.call(wrapped)
            } else {
                configClosure.delegate = wrapped
                configClosure.resolveStrategy = Closure.DELEGATE_ONLY
                configClosure.call()
            }
        }

        // apply cookieHandler cookies if no explicit Cookie header was set
        if (!wrapped.hasHeader("Cookie") && !cookieHandler.isEmpty()) {
            wrapped.header("Cookie", cookieHandler.asHeaderValue())
        }

        HttpRequest request = wrapped.unwrap().build()

        return executeWithCircuitBreaker {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply { response ->
                        log.debug("Received HTTP {} from {} ({} chars)",
                                response.statusCode(), uri, response.body()?.length() ?: 0)

                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }

                        if (maxResponseBytes != Long.MAX_VALUE &&
                                response.body() != null &&
                                response.body().length() > maxResponseBytes) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(413, "Body too large")
                        }

                        new HttpClientResponse(response)
                    }
        }
    }

    // -------------------------------------------------------------------------
    // Multipart encoding helpers
    // -------------------------------------------------------------------------

    private static HttpRequest.BodyPublisher buildMultipartBody(List<MultipartPart> parts, String boundary) {
        def byteArrays = []
        parts.each { MultipartPart part ->
            def header = """--${boundary}\r
Content-Disposition: form-data; name="${part.name}"; filename="${part.filename}"\r
Content-Type: ${part.contentType}\r
\r
""".getBytes("UTF-8")
            byteArrays << header
            byteArrays << part.data
            byteArrays << "\r\n".getBytes("UTF-8")
        }
        byteArrays << "--${boundary}--\r\n".getBytes("UTF-8")
        HttpRequest.BodyPublishers.ofByteArrays(byteArrays)
    }

    /**
     * DSL builder for multipart parts + headers.
     *
     * Used like:
     *
     *   client.postMultipartSync("/upload") { b ->
     *       b.part {
     *           name "file1"
     *           filename "readme.txt"
     *           content "Hello"
     *       }
     *       b.header("X-Test", "dsl")
     *   }
     */
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

        void header(String name, String value) {
            headers[name] << value
        }
    }

    // async multipart using DSL (A)
    CompletableFuture<HttpClientResponse> postMultipart(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = MultipartDSLBuilder) Closure dsl
    ) {
        def mb = new MultipartDSLBuilder()

        if (dsl.maximumNumberOfParameters == 1) {
            dsl.call(mb)          // matches { b -> ... } in tests
        } else {
            dsl.delegate = mb
            dsl.resolveStrategy = Closure.DELEGATE_ONLY
            dsl.call()
        }

        return postMultipart(path, mb.parts) { rb ->
            mb.headers.each { k, vs -> vs.each { v -> rb.header(k, v) } }
        }
    }

    // sync multipart using DSL (A)
    HttpClientResponse postMultipartSync(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = MultipartDSLBuilder) Closure dsl,
            Duration timeout = DEFAULT_SYNC_TIMEOUT
    ) {
        postMultipart(path, dsl).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // async multipart with explicit List<MultipartPart> (B + C)
    CompletableFuture<HttpClientResponse> postMultipart(
            String path,
            List<MultipartPart> parts,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        if (!parts || parts.isEmpty()) {
            throw new IllegalArgumentException("Multipart parts are required for postMultipart")
        }

        def uri = resolveUri(path)
        def boundary = "----GroovyBoundary${System.currentTimeMillis()}"
        log.info("Sending multipart POST to {} with boundary {}", uri, boundary)

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)

        def wrapped = new GroovyRequestBuilder(requestBuilder)

        defaultHeaders.each { name, values ->
            values.each { v -> wrapped.header(name, v) }
        }

        if (configClosure) {
            if (configClosure.maximumNumberOfParameters == 1) {
                configClosure.call(wrapped)
            } else {
                configClosure.delegate = wrapped
                configClosure.resolveStrategy = Closure.DELEGATE_ONLY
                configClosure.call()
            }
        }

        def bodyPublisher = buildMultipartBody(parts, boundary)
        wrapped.header("Content-Type", "multipart/form-data; boundary=${boundary}")

        // apply cookieHandler if needed
        if (!wrapped.hasHeader("Cookie") && !cookieHandler.isEmpty()) {
            wrapped.header("Cookie", cookieHandler.asHeaderValue())
        }

        HttpRequest request = wrapped.unwrap().POST(bodyPublisher).build()

        return executeWithCircuitBreaker {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply { response ->
                        log.debug("Multipart POST received HTTP {} from {}", response.statusCode(), uri)
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        if (maxResponseBytes != Long.MAX_VALUE &&
                                response.body() != null &&
                                response.body().length() > maxResponseBytes) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(413, "Body too large")
                        }
                        new HttpClientResponse(response)
                    }
        }
    }

    // sync multipart with explicit parts (B)
    HttpClientResponse postMultipartSync(
            String path,
            List<MultipartPart> parts,
            Closure configClosure = null,
            Duration timeout = DEFAULT_SYNC_TIMEOUT
    ) {
        postMultipart(path, parts, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // -------------------------------------------------------------------------
    // File download support (downloadBytesSync used in tests)
    // -------------------------------------------------------------------------

    CompletableFuture<byte[]> downloadBytes(String path) {
        def uri = resolveUri(path)
        log.info("Downloading file from {}", uri)

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout ?: DEFAULT_REQUEST_TIMEOUT)
                .GET()

        def wrapped = new GroovyRequestBuilder(requestBuilder)

        defaultHeaders.each { name, values ->
            values.each { v -> wrapped.header(name, v) }
        }

        // cookies via cookieHandler
        if (!wrapped.hasHeader("Cookie") && !cookieHandler.isEmpty()) {
            wrapped.header("Cookie", cookieHandler.asHeaderValue())
        }

        HttpRequest request = wrapped.unwrap().build()

        return executeWithCircuitBreaker {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply { response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            String bodyText = response.body() ?
                                    new String(response.body(), "UTF-8") : ""
                            throw new HttpResponseException(response.statusCode(), bodyText)
                        }
                        if (maxResponseBytes != Long.MAX_VALUE &&
                                response.body() != null &&
                                response.body().length > maxResponseBytes) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(413, "Body too large")
                        }
                        log.debug("Downloaded {} bytes from {}", response.body().length, uri)
                        response.body()
                    }
        }
    }

    byte[] downloadBytesSync(String path, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        downloadBytes(path).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // -------------------------------------------------------------------------
    // Async HTTP verbs (string body)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Sync HTTP verbs
    // -------------------------------------------------------------------------

    HttpClientResponse getSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        get(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse postSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        post(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse putSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        put(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse deleteSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        delete(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse patchSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        patch(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse headSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        head(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    HttpClientResponse optionsSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        options(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    // -------------------------------------------------------------------------
    // URI resolution with allowedHosts guard
    // -------------------------------------------------------------------------

    private URI resolveUri(String path) {
        if (!path) return host

        if (path.toLowerCase().startsWith("http://") || path.toLowerCase().startsWith("https://")) {
            if (!allowAbsoluteUrls) {
                throw new IllegalArgumentException("Absolute URLs disabled by configuration")
            }
            def uri = new URI(path)
            if (!allowedHosts.isEmpty() && !allowedHosts.contains(uri.host)) {
                throw new IllegalArgumentException("Target host not allowed: ${uri.host}")
            }
            return uri
        }

        String base = host.toString().endsWith('/') ? host.toString()[0..-2] : host.toString()
        String p = path.startsWith('/') ? path : "/$path"
        URI.create(base + p)
    }

    // -------------------------------------------------------------------------
    // Circuit breaker wrapper
    // -------------------------------------------------------------------------

    private <T> CompletableFuture<T> executeWithCircuitBreaker(Supplier<CompletableFuture<T>> supplier) {
        if (circuitBreaker.isOpen()) {
            log.warn("Circuit breaker is OPEN — rejecting request")
            CompletableFuture<T> future = new CompletableFuture<>()
            future.completeExceptionally(new CircuitOpenException("Circuit is open"))
            return future
        }

        try {
            CompletableFuture<T> future = supplier.get()
            return future.handle((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = throwable
                    while (cause instanceof CompletionException && cause.cause != null) {
                        cause = cause.cause
                    }

                    circuitBreaker.recordFailure()
                    log.warn("Request failed, recorded circuit-breaker failure: {}", cause.toString())

                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause
                    } else {
                        throw new CompletionException(cause)
                    }
                }
                result
            })
        } catch (Exception e) {
            circuitBreaker.recordFailure()
            log.warn("Synchronous failure during HTTP execution, recorded circuit-breaker failure: {}", e.toString())
            CompletableFuture<T> future = new CompletableFuture<>()
            future.completeExceptionally(e)
            future
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    void close() {
        // HttpClient doesn't need explicit close
    }

    String toString(path = '/') {
        resolveUri(path).toString()
    }

    // -------------------------------------------------------------------------
    // Inner classes: CircuitBreaker & exceptions
    // -------------------------------------------------------------------------

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
            failureCount.get() >= failureThreshold
        }

        void recordFailure() {
            int currentFailures = failureCount.incrementAndGet()
            if (currentFailures >= failureThreshold) {
                openStateTime = System.currentTimeMillis()
            }
        }

        void reset() {
            failureCount.set(0)
            openStateTime = 0
        }
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
}
