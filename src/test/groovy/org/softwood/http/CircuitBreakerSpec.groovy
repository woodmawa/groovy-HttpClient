package org.softwood.http
import spock.lang.Specification
import spock.lang.Unroll
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

class CircuitBreakerSpec extends Specification {

    /**
     * Helper to create a CircuitBreaker using the SecurityConfig builders.
     */
    private GroovyHttpClient.CircuitBreaker createBreaker(int threshold, long timeoutMs) {
        // Use the static constructor builder from SecurityConfig
        def config = SecurityConfig.testing("http://test.api")
                .withOverrides([
                        failureThreshold: threshold,
                        resetTimeoutMs: timeoutMs
                ])


        // use static builder method to  instantiate the static inner class
        def cb = GroovyHttpClient.CircuitBreaker.create(config)

        return cb

    }

    def "circuit breaker should start in closed state"() {
        given:
        def cb = createBreaker(5, 30000)

        expect:
        !cb.isOpen()
        cb.failureCount == 0
        cb.openStateTime == 0L
    }

    def "circuit breaker should open after threshold failures"() {
        given:
        def threshold = 3
        def cb = createBreaker(threshold, 30000)

        when: "Recording failures up to threshold"
        threshold.times { cb.recordFailure() }

        then: "Circuit should be open"
        cb.isOpen()
        cb.failureCount == threshold
        cb.openStateTime > 0L
    }

    def "circuit breaker should not open before threshold failures"() {
        given:
        def threshold = 3
        def cb = createBreaker(threshold, 30000)

        when: "Recording failures below threshold"
        (threshold - 1).times { cb.recordFailure() }

        then: "Circuit should remain closed"
        !cb.isOpen()
        cb.failureCount == threshold - 1
        cb.openStateTime == 0L
    }

    def "circuit breaker should close after reset timeout"() {
        given:
        def resetTimeoutMs = 100
        def cb = createBreaker(3, resetTimeoutMs)

        when: "Open the circuit"
        3.times { cb.recordFailure() }

        then: "Circuit should be open"
        cb.isOpen()

        when: "Wait for reset timeout"
        Thread.sleep(resetTimeoutMs + 50)

        then: "Circuit should auto-close on next check"
        !cb.isOpen()
        cb.failureCount == 0
    }

    def "reset method should clear failure count and time"() {
        given:
        def cb = createBreaker(3, 30000)

        when: "Open the circuit"
        3.times { cb.recordFailure() }

        then: "State is open"
        cb.isOpen()

        when: "Reset is called"
        cb.reset()

        then: "Circuit should be clean"
        !cb.isOpen()
        cb.failureCount == 0
        cb.openStateTime == 0L
    }

    @Unroll
    def "circuit breaker with threshold #threshold should open after exactly #threshold failures"() {
        given:
        def cb = createBreaker(threshold, 30000)

        when:
        (threshold - 1).times { cb.recordFailure() }

        then: "Still closed"
        !cb.isOpen()

        when: "One more failure"
        cb.recordFailure()

        then: "Now open"
        cb.isOpen()

        where:
        threshold << [1, 3, 5, 10]
    }
}

// Integration test that uses the actual GroovyHttpClient class
class GroovyHttpClientCircuitBreakerIntegrationSpec extends Specification {
    def "circuit breaker should prevent calls when open"() {
        given:
        /* FIX 1: Use SecurityConfig instead of the old multi-argument constructor.
           The current GroovyHttpClient  requires a SecurityConfig.
        */
        def config = new SecurityConfig(
                baseUrl: "http://example.com",
                failureThreshold: 2, // Very low threshold for testing
                resetTimeoutMs: 30000
        )
        def client = new GroovyHttpClient(config)

        and: "A mock HttpClient to simulate network failures"
        def mockServer = Mock(java.net.http.HttpClient)

        // Use reflection to replace the private final httpClient [cite: 343]
        def httpClientField = GroovyHttpClient.class.getDeclaredField("httpClient")
        httpClientField.setAccessible(true)
        httpClientField.set(client, mockServer)

        // Make the mock always throw an IOException
        mockServer.sendAsync(_, _) >> {
            CompletableFuture.failedFuture(new IOException("Simulated network failure"))
        }

        when: "We make calls to reach the threshold"
        def firstException = null
        def secondException = null
        def thirdException = null

        try {
            client.get("/test").join()
        } catch (Exception e) {
            firstException = unwrapCompletionException(e)
        }

        try {
            client.get("/test").join()
        } catch (Exception e) {
            secondException = unwrapCompletionException(e)
        }

        /* FIX 2: Logic alignment.
           With a threshold of 2, the second failure  sets 'openStateTime'.
           The VERY NEXT call will find the circuit open[cite: 428, 436].
        */
        try {
            client.get("/test").join() // This should now fail with CircuitOpenException
        } catch (Exception e) {
            thirdException = unwrapCompletionException(e)
        }

        then: "The first two failures should be the simulated network errors"
        firstException instanceof IOException
        secondException instanceof IOException

        and: "The third call should immediately fail because the circuit is open"
        thirdException != null

        // Find the CircuitOpenException class via reflection [cite: 463]
        def circuitOpenExceptionClass = GroovyHttpClient.class.getDeclaredClasses().find {
            it.simpleName == "CircuitOpenException"
        }

        circuitOpenExceptionClass.isInstance(thirdException)
        thirdException.message == "Circuit is OPEN"
    }

    // Helper method to unwrap CompletionException [cite: 464]
    private Throwable unwrapCompletionException(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.cause != null) {
            return t.cause
        }
        return t
    }
}