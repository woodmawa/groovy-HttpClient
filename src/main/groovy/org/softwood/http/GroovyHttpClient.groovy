package org.softwood.http

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
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

class GroovyHttpClient implements AutoCloseable  {
    private final URI host
    private final HttpClient httpClient
    private final CircuitBreaker circuitBreaker

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

    // Add this method to GroovyHttpClient class to make testing easier
    static HttpClient createInsecureHttpClient(ThreadFactory threadFactory, Duration connectTimeout) {
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
            return HttpClient.newBuilder()
                    .executor(Executors.newThreadPerTaskExecutor(threadFactory))
                    .connectTimeout(connectTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .build()
        } catch (Exception e) {
            throw new RuntimeException("Could not create insecure HTTP client", e)
        }
    }

    /**
     * Creates a new HTTP client with the specified base URL
     *
     * @param baseUrl The base URL for all requests
     * @param connectTimeout Optional connection timeout
     * @param requestTimeout Optional request timeout
     * @param failureThreshold Optional circuit breaker failure threshold
     * @param resetTimeoutMs Optional circuit breaker reset timeout in milliseconds
     * @param sslContext Optional SSLContext to use (e.g., for trusting all certificates in tests)
     */

    GroovyHttpClient(String host,
                     Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT,
                     Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT,
                     int failureThreshold = DEFAULT_FAILURE_THRESHOLD,
                     long resetTimeoutMs = DEFAULT_RESET_TIMEOUT_MS,
                     SSLContext sslContext = null,
                     HostnameVerifier hostnameVerifier = null
    ) {

        if (!host) {
            throw new IllegalArgumentException("Base URL cannot be null or empty")
        }

        try {
            this.host = new URI(host)
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL format: $host", e)
        }

        // Initialize circuit breaker first to prevent NPE
        this.circuitBreaker = new CircuitBreaker(failureThreshold, resetTimeoutMs)

        // Create thread factory for virtual threads
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("http-client-", threadCounter.toLong())
                .factory()

        // Create a trust manager that trusts all certificates
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0]
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        // No validation
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        // No validation
                    }
                }
        }

        // Create a custom SSL context that trusts all certificates
        SSLContext customSSLContext
        try {
            customSSLContext = SSLContext.getInstance("TLS")
            customSSLContext.init(null, trustAllCerts, new SecureRandom())
        } catch (Exception e) {
            throw new RuntimeException("Error initializing SSL context", e)
        }

        // Use the proper class for static method calls in Groovy
        // This ensures we're calling the static method on the correct class
        def connectionClass = Class.forName("javax.net.ssl.HttpsURLConnection")

        // Create a hostname verifier that accepts all hostnames
        HostnameVerifier trustAllHostnames = new HostnameVerifier() {
            @Override
            boolean verify(String hostname, SSLSession session) {
                return true
            }
        }

        // For legacy Java API compatibility, set the default hostname verifier
        //todo only ok for tests
        // Call the static method properly
        connectionClass.getDeclaredMethod("setDefaultHostnameVerifier", HostnameVerifier.class)
                .invoke(null, trustAllHostnames)


        // Initialize the HttpClient.Builder with our custom SSL context
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(virtualThreadFactory))
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_2)
                .sslContext(sslContext ?: customSSLContext)

        // Create and configure SSLParameters to disable hostname verification
        //By explicitly setting setApplicationProtocols(["h2", "http/1.1"]), you're telling the SSL handshake to negotiate HTTP/2 ("h2") first, falling back to HTTP/1.1 if needed
        SSLParameters sslParameters = new SSLParameters()
        sslParameters.setEndpointIdentificationAlgorithm(null)
        sslParameters.setApplicationProtocols(["h2", "http/1.1"] as String[])
        clientBuilder.sslParameters(sslParameters)

        // Build the HttpClient
        this.httpClient = clientBuilder.build()

        // Register a shutdown hook to ensure resources are cleaned up
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted({
                    try {
                        this.close()
                    } catch (Exception ignored) {
                        // Ignore exceptions during shutdown
                    }
                } as Runnable)
        )
    }

    /**
     * take a well formed url and get the host from that and reuse
     * @param url
     */
    GroovyHttpClient (URL url) {
        if (!url) {
            throw new MalformedURLException("null url passed ")
        }

        this.host = url.toURI()

        // Create thread factory for virtual threads
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("http-client-", threadCounter.toLong())
                .factory()

        // Create the HttpClient with virtual threads
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(virtualThreadFactory))
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_2)  // Explicitly enable HTTP/2
                .build()

        this.circuitBreaker = new CircuitBreaker(DEFAULT_FAILURE_THRESHOLD, DEFAULT_RESET_TIMEOUT_MS)

        // Register a shutdown hook to ensure resources are cleaned up
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted({
                    try {
                        this.close()
                    } catch (Exception ignored) {
                        // Ignore exceptions during shutdown
                    }
                } as Runnable)
        )
    }

    //-------------------------------------------------------------------------
    // Asynchronous HTTP Methods
    //-------------------------------------------------------------------------

    /**
     * Performs an asynchronous GET request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response body as String
     */
    CompletableFuture<String> get(String path, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .GET()
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        return response.body()
                    })
        }
    }

    /**
     * Performs an asynchronous POST request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response body as String
     */
    CompletableFuture<String> post(String path, String body, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        return response.body()
                    })
        }
    }

    /**
     * Performs an asynchronous PUT request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response body as String
     */
    CompletableFuture<String> put(String path, String body, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        return response.body()
                    })
        }
    }

    /**
     * Performs an asynchronous DELETE request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response body as String
     */
    CompletableFuture<String> delete(String path, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .DELETE()
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        return response.body()
                    })
        }
    }

    /**
     * Performs an asynchronous PATCH request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response body as String
     */
    CompletableFuture<String> patch(String path, String body, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), response.body())
                        }
                        return response.body()
                    })
        }
    }

    /**
     * Performs an asynchronous HEAD request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with HttpResponse
     */
    CompletableFuture<HttpResponse<Void>> head(String path, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), "HTTP error")
                        }
                        return response
                    })
        }
    }

    /**
     * Performs an asynchronous OPTIONS request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @return CompletableFuture with the response headers
     */
    CompletableFuture<HttpResponse<Void>> options(String path, Closure configClosure = null) {
        return executeWithCircuitBreaker { ->
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(resolveUri(path))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .timeout(DEFAULT_REQUEST_TIMEOUT)

            if (configClosure) {
                configClosure.delegate = requestBuilder
                configClosure.resolveStrategy = Closure.DELEGATE_FIRST
                configClosure()
            }

            HttpRequest request = requestBuilder.build()
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply({ response ->
                        if (response.statusCode() >= 400) {
                            circuitBreaker.recordFailure()
                            throw new HttpResponseException(response.statusCode(), "HTTP error")
                        }
                        return response
                    })
        }
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
     * @return Response body as String
     */
    String getSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return get(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous POST request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response body as String
     */
    String postSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return post(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous PUT request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response body as String
     */
    String putSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return put(path, body, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous DELETE request
     *
     * @param path The path to append to the base URL
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response body as String
     */
    String deleteSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return delete(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Performs a synchronous PATCH request
     *
     * @param path The path to append to the base URL
     * @param body The request body
     * @param configClosure Optional closure for configuring the request
     * @param timeout Optional timeout duration
     * @return Response body as String
     */
    String patchSync(String path, String body, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
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
    HttpResponse<Void> headSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
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
    HttpResponse<Void> optionsSync(String path, Closure configClosure = null, Duration timeout = DEFAULT_SYNC_TIMEOUT) {
        return options(path, configClosure).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Resolves a path against the base URL
     */
    private URI resolveUri(String path) {
        if (!path) {
            return host
        }

        // NEW: Check if the path is an absolute URL
        if (path.toLowerCase().startsWith('http://') || path.toLowerCase().startsWith('https://')) {
            try {
                return new URI(path)
            } catch (Exception e) {
                // Propagate as a clear error
                throw new IllegalArgumentException("Invalid absolute URL provided as path: $path", e)
            }
        }

        String pathToUse = path.startsWith('/') ? path.substring(1) : path
        return URI.create("${host}${host.toString().endsWith('/') ? '' : '/'}${pathToUse}")
    }

    /**
     * Executes an HTTP operation with circuit breaker protection
     *
     * Fixed exception handling throughout the code to properly propagate the root cause of failures
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

    /**
     * Circuit breaker implementation to prevent repeated calls to failing services
     *
     * Modified the isOpen() method to also check the current failure count against the threshold
     * Added failure recording in each HTTP method's response handling logic for 4xx/5xx responses
     * Enhanced exception handling in the executeWithCircuitBreaker method to properly unwrap nested exceptions
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