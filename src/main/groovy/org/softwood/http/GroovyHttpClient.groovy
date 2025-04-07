package org.softwood.http

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10)
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30)

    // Default synchronous method timeout
    private static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(30)

    // Circuit breaker defaults
    private static final int DEFAULT_FAILURE_THRESHOLD = 5
    private static final long DEFAULT_RESET_TIMEOUT_MS = 30000

    /**
     * Creates a new HTTP client with the specified base URL
     *
     * @param baseUrl The base URL for all requests
     * @param connectTimeout Optional connection timeout
     * @param requestTimeout Optional request timeout
     * @param failureThreshold Optional circuit breaker failure threshold
     * @param resetTimeoutMs Optional circuit breaker reset timeout in milliseconds
     */
    GroovyHttpClient(String host,
                     Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT,
                     Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT,
                     int failureThreshold = DEFAULT_FAILURE_THRESHOLD,
                     long resetTimeoutMs = DEFAULT_RESET_TIMEOUT_MS) {

        if (!host) {
            throw new IllegalArgumentException("Base URL cannot be null or empty")
        }

        try {
            this.host = new URI(host)
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL format: $host", e)
        }

        // Create thread factory for virtual threads
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("http-client-", threadCounter.toLong())
                .factory()

        // Create the HttpClient with virtual threads
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newThreadPerTaskExecutor(virtualThreadFactory))
                .connectTimeout(connectTimeout)
                .build()

        this.circuitBreaker = new CircuitBreaker(failureThreshold, resetTimeoutMs)

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
        if (url) {
            host = url.host
            //add regex test for host ?
        }
        else {
            throw new MalformedURLException("null url passed ")
        }

        GroovyHttpClient(host,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_FAILURE_THRESHOLD,
                DEFAULT_RESET_TIMEOUT_MS)

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

        String pathToUse = path.startsWith('/') ? path.substring(1) : path
        return URI.create("${host}${host.toString().endsWith('/') ? '' : '/'}${pathToUse}")
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
                    Throwable cause = (throwable instanceof CompletionException) ? throwable.getCause() : throwable
                    circuitBreaker.recordFailure()
                    throw cause
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

    String toString (path='/') {
        resolveUri(path).toString()
    }

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
            return false
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
