package org.softwood.http

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
 * An HTTP client implementation that uses Java 21 virtual threads for non-blocking,
 * highly scalable HTTP operations. It provides a fluent API with closures for
 * configuring requests and circuit breaker patterns for resilience.
 */

/**
 * An HTTP client implementation that uses Java 21 virtual threads for non-blocking,
 * highly scalable HTTP operations. It provides a fluent API with closures for
 * configuring requests and circuit breaker patterns for resilience.
 */
class GroovyHttpClient implements AutoCloseable {
    private final URI host
    private final HttpClient httpClient
    private final CircuitBreaker circuitBreaker
    private final CookieManager cookieManager

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

    GroovyHttpClient(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_FAILURE_THRESHOLD, DEFAULT_RESET_TIMEOUT_MS, null, null)
    }

    /**
     * generic constructor for variable args that then calls the full constructor
     * @param args
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

        // Initialize cookie manager
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
            this.httpClient = createInsecureHttpClient(tf, connectTimeout, cookieManager)
        }
    }

    /**
     * Groovy-friendly wrapper around Java's HttpRequest.Builder.
     *
     * Groovy's dynamic method resolution cannot always invoke Java methods directly
     * (especially from classes in java.* modules like java.net.http). As a result,
     * calling 'header(...)' inside a closure delegate bound to HttpRequest.Builder
     * may fail or fallback to the closure's owner.
     *
     * This wrapper exposes selected builder methods explicitly, and uses
     * `methodMissing` to forward any others, so that Groovy closures can call
     * builder-style methods dynamically in DSL form:
     *
     *   client.get("/products") {
     *       header "Accept", "application/json"
     *       header "X-API-Key", "test-key"
     *   }
     *
     * Without this wrapper, the closure delegate would resolve to the test spec
     * or outer context, and calls like 'header()' would not reach the real builder.
     */
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

        /**
         * Add a cookie to this specific request
         */
        void cookie(String name, String value) {
            header("Cookie", "${name}=${value}")
        }

        /**
         * Add multiple cookies to this request
         */
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

    /**
     * Response wrapper that includes body, headers, and status code
     */
    static class HttpClientResponse {
        final String body
        final Map<String, List<String>> headers
        final int statusCode

        HttpClientResponse(HttpResponse<String> response) {
            this.body = response.body()
            this.headers = response.headers().map()
            this.statusCode = response.statusCode()
        }

        /**
         * Get the first value of a header (case-insensitive)
         */
        String getHeader(String name) {
            def entry = headers.find { k, v -> k.equalsIgnoreCase(name) }
            return entry?.value?.get(0)
        }

        /**
         * Get all values of a header (case-insensitive)
         */
        List<String> getHeaders(String name) {
            headers.findAll { k, v -> k.equalsIgnoreCase(name) }
                    .values()                       // get all List<String> values
                    .flatten()  as List<String>     // combine them into a single List<String>
        }

        /**
         * Check if a header exists (case-insensitive)
         */
        boolean hasHeader(String name) {
            return headers.any { k, v -> k.equalsIgnoreCase(name) }
        }

        @Override
        String toString() {
            return body
        }
    }

    //-------------------------------------------------------------------------
    // Cookie Management API
    //-------------------------------------------------------------------------

    /**
     * Get all cookies for the base URL
     */
    List<HttpCookie> getCookies() {
        cookieManager.cookieStore.get(host).collect()
    }

    /**
     * Get a specific cookie by name
     */
    HttpCookie getCookie(String name) {
        cookieManager.cookieStore.get(host).find { it.name == name }
    }

    /**
     * Add a cookie manually
     */
    GroovyHttpClient addCookie(String name, String value, String path = "/") {
        def cookie = new HttpCookie(name, value)
        cookie.path = path
        cookie.domain = host.host
        cookieManager.cookieStore.add(host, cookie)
        return this
    }

    /**
     * Add a cookie with full control
     */
    GroovyHttpClient addCookie(HttpCookie cookie) {
        cookieManager.cookieStore.add(host, cookie)
        return this
    }

    /**
     * Remove a specific cookie by name
     */
    GroovyHttpClient removeCookie(String name) {
        def cookies = cookieManager.cookieStore.get(host)
        cookies.findAll { it.name == name }.each { cookie ->
            cookieManager.cookieStore.remove(host, cookie)
        }
        return this
    }

    /**
     * Clear all cookies
     */
    GroovyHttpClient clearCookies() {
        cookieManager.cookieStore.removeAll()
        return this
    }

    /**
     * Set cookie policy (ACCEPT_ALL, ACCEPT_NONE, ACCEPT_ORIGINAL_SERVER)
     */
    GroovyHttpClient setCookiePolicy(CookiePolicy policy) {
        cookieManager.setCookiePolicy(policy)
        return this
    }

    /**
     * Get current cookie policy
     */
    CookiePolicy getCookiePolicy() {
        return cookieManager.cookiePolicy
    }

    /**
     * Get cookie store for advanced operations
     */
    CookieStore getCookieStore() {
        return cookieManager.cookieStore
    }

    //-------------------------------------------------------------------------
    // Default Header Management API
    //-------------------------------------------------------------------------

    /**
     * Add a default header that will be included in all requests
     */
    GroovyHttpClient withHeader(String name, String value) {
        defaultHeaders.computeIfAbsent(name) { [] } << value
        return this
    }

    /**
     * Replace default header (single value)
     */
    GroovyHttpClient setHeader(String name, String value) {
        defaultHeaders[name] = [value]
        return this
    }

    /**
     * Add multiple default headers (Map<String, String>)
     */
    GroovyHttpClient withHeaders(Map<String, String> headers) {
        headers.each { k, v ->
            defaultHeaders.computeIfAbsent(k) { [] } << v
        }
        return this
    }

    /**
     * Replace all default headers
     */
    GroovyHttpClient setHeaders(Map<String, String> headers) {
        defaultHeaders.clear()
        headers.each { k, v -> defaultHeaders[k] = [v] }
        return this
    }

    /**
     * Clear all default headers
     */
    GroovyHttpClient clearHeaders() {
        defaultHeaders.clear()
        return this
    }

    /**
     * Get a copy of the default headers
     */
    Map<String, List<String>> getDefaultHeaders() {
        defaultHeaders.collectEntries { k, v -> [(k): v.toList()] }
    }

    //-------------------------------------------------------------------------
    // HTTP Client Creation
    //-------------------------------------------------------------------------

    static HttpClient createInsecureHttpClient(ThreadFactory threadFactory, Duration connectTimeout, CookieManager cookieManager = null) {
        // Create a trust manager that trusts all certificates
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        }

        try {
            // Create an SSL context that trusts all
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, new SecureRandom())

            // Create SSL parameters that don't validate hostnames
            SSLParameters sslParameters = new SSLParameters()
            sslParameters.setEndpointIdentificationAlgorithm(null)
            sslParameters.setApplicationProtocols(["h2", "http/1.1"] as String[])

            // Create the actual client with these settings
            def builder = HttpClient.newBuilder()
                    .executor(Executors.newThreadPerTaskExecutor(threadFactory))
                    .connectTimeout(connectTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)

            if (cookieManager) {
                builder.cookieHandler(cookieManager)
            }

            return builder.build()
        } catch (Exception e) {
            throw new RuntimeException("Could not create insecure HTTP client", e)
        }
    }

    //-------------------------------------------------------------------------
    // Core Request Handler
    //-------------------------------------------------------------------------

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
                requestBuilder.GET()
                break
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
                break
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body ?: ""))
                break
            case "PATCH":
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: ""))
                break
            case "DELETE":
                requestBuilder.DELETE()
                break
            case "HEAD":
                requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                break
            case "OPTIONS":
                requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                break
            default:
                requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
        }

        // Wrap builder for Groovy closure DSL
        def wrappedRequestBuilder = new GroovyRequestBuilder(requestBuilder)

        // Apply default headers unconditionally to the wrapped builder
        defaultHeaders.each { name, values ->
            values.each { v ->
                wrappedRequestBuilder.header(name, v)
            }
        }

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
                        return new HttpClientResponse(response)
                    }
        }
    }

    //-------------------------------------------------------------------------
    // Asynchronous HTTP Methods
    //-------------------------------------------------------------------------

    /**
     * Performs an asynchronous GET request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response (body and headers)
     */
    CompletableFuture<HttpClientResponse> get(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("GET", path, null, configClosure)
    }

    /**
     * Performs an asynchronous POST request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response (body and headers)
     */
    CompletableFuture<HttpClientResponse> post(
            String path,
            String body,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("POST", path, body, configClosure)
    }

    /**
     * Performs an asynchronous PUT request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response (body and headers)
     */
    CompletableFuture<HttpClientResponse> put(
            String path,
            String body,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("PUT", path, body, configClosure)
    }

    /**
     * Performs an asynchronous PATCH request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response (body and headers)
     */
    CompletableFuture<HttpClientResponse> patch(
            String path,
            String body,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("PATCH", path, body, configClosure)
    }

    /**
     * Performs an asynchronous DELETE request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response (body and headers)
     */
    CompletableFuture<HttpClientResponse> delete(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("DELETE", path, null, configClosure)
    }

    /**
     * Performs an asynchronous HEAD request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with HttpResponse
     */
    CompletableFuture<HttpClientResponse> head(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("HEAD", path, null, configClosure)
    }

    /**
     * Performs an asynchronous OPTIONS request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response
     */
    CompletableFuture<HttpClientResponse> options(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = GroovyRequestBuilder) Closure configClosure = null
    ) {
        sendRequest("OPTIONS", path, null, configClosure)
    }

    //-------------------------------------------------------------------------
    // Synchronous HTTP Methods
    //-------------------------------------------------------------------------

    /**
     * Performs a synchronous GET request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response with body and headers
     */
    HttpClientResponse getSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return get(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous POST request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response with body and headers
     */
    HttpClientResponse postSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return post(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous PUT request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response with body and headers
     */
    HttpClientResponse putSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return put(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous DELETE request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response with body and headers
     */
    HttpClientResponse deleteSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return delete(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous PATCH request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response with body and headers
     */
    HttpClientResponse patchSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return patch(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous HEAD request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return HttpResponse
     */
    HttpClientResponse headSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return head(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous OPTIONS request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return HttpResponse
     */
    HttpClientResponse optionsSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return options(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    //-------------------------------------------------------------------------
    // Helper Methods
    //-------------------------------------------------------------------------

    /**
     * Resolves a path against the base URL
     */
    private URI resolveUri(String path) {
        if (!path) {
            return host
        }

        // Check if the path is an absolute URL
        if (path.toLowerCase().startsWith('http://') || path.toLowerCase().startsWith('https://')) {
            try {
                return new URI(path)
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid absolute URL provided as path: $path", e)
            }
        }

        String hostStr = host.toString().endsWith('/') ? host.toString()[0..-2] : host.toString()
        String pathStr = path.startsWith('/') ? path : "/$path"
        return URI.create(hostStr + pathStr)
    }

    /**
     * Executes an HTTP operation with circuit breaker protection
     */
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

    /**
     * Closes the HTTP client and releases resources
     */
    @Override
    void close() {
        // HttpClient doesn't have a close method in Java 21, but we can
        // provide a hook for future cleanup operations if needed
    }

    String toString(path='/') {
        resolveUri(path).toString()
    }

    //-------------------------------------------------------------------------
    // Inner Classes
    //-------------------------------------------------------------------------

    /**
     * Circuit breaker implementation to prevent repeated calls to failing services
     */
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
                // If circuit has been open longer than reset timeout, try to close it
                if (System.currentTimeMillis() - openStateTime > resetTimeoutMs) {
                    reset()
                    return false
                }
                return true
            }

            // Also check if we've hit the threshold
            return failureCount.get() >= failureThreshold
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

    /**
     * Exception thrown when the circuit breaker is open
     */
    static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String message) {
            super(message)
        }
    }

    /**
     * Exception for HTTP response errors
     */
    static class HttpResponseException extends RuntimeException {
        final int statusCode

        HttpResponseException(int statusCode, String responseBody) {
            super("HTTP error: $statusCode, body: $responseBody")
            this.statusCode = statusCode
        }
    }
}