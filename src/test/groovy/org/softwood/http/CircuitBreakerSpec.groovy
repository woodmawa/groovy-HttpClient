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
    // Inner class to hold our CircuitBreaker instance
    class CircuitBreakerHolder {
        def circuitBreakerInstance
        def failureCountField
        def openStateTimeField
        def isOpenMethod
        def recordFailureMethod
        def resetMethod
    }

    def setupCircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        // Get the CircuitBreaker class using reflection via the parent class
        def groovyHttpClientClass = GroovyHttpClient.class
        def circuitBreakerClass = groovyHttpClientClass.getDeclaredClasses().find {
            it.simpleName == "CircuitBreaker"
        }

        if (!circuitBreakerClass) {
            throw new RuntimeException("CircuitBreaker inner class not found")
        }

        // Get constructor
        def constructor = circuitBreakerClass.getDeclaredConstructor(int.class, long.class)
        constructor.setAccessible(true)

        // Create instance
        def circuitBreaker = constructor.newInstance(failureThreshold, resetTimeoutMs)

        // Get fields and methods
        def failureCountField = circuitBreakerClass.getDeclaredField("failureCount")
        failureCountField.setAccessible(true)

        def openStateTimeField = circuitBreakerClass.getDeclaredField("openStateTime")
        openStateTimeField.setAccessible(true)

        def isOpenMethod = circuitBreakerClass.getDeclaredMethod("isOpen")
        isOpenMethod.setAccessible(true)

        def recordFailureMethod = circuitBreakerClass.getDeclaredMethod("recordFailure")
        recordFailureMethod.setAccessible(true)

        def resetMethod = circuitBreakerClass.getDeclaredMethod("reset")
        resetMethod.setAccessible(true)

        def holder = new CircuitBreakerHolder(
                circuitBreakerInstance: circuitBreaker,
                failureCountField: failureCountField,
                openStateTimeField: openStateTimeField,
                isOpenMethod: isOpenMethod,
                recordFailureMethod: recordFailureMethod,
                resetMethod: resetMethod
        )

        return holder
    }

    def "circuit breaker should start in closed state"() {
        given:
        def cb = setupCircuitBreaker(5, 30000)

        expect:
        !cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() == 0
        cb.openStateTimeField.get(cb.circuitBreakerInstance) == 0L
    }

    def "circuit breaker should open after threshold failures"() {
        given:
        def failureThreshold = 3
        def cb = setupCircuitBreaker(failureThreshold, 30000)

        when: "Recording failures up to threshold"
        for (int i = 0; i < failureThreshold; i++) {
            cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)
        }

        then: "Circuit should be open"
        cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() == failureThreshold
        cb.openStateTimeField.get(cb.circuitBreakerInstance) > 0L
    }

    def "circuit breaker should not open before threshold failures"() {
        given:
        def failureThreshold = 3
        def cb = setupCircuitBreaker(failureThreshold, 30000)

        when: "Recording failures below threshold"
        for (int i = 0; i < failureThreshold - 1; i++) {
            cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)
        }

        then: "Circuit should remain closed"
        !cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() == failureThreshold - 1
        cb.openStateTimeField.get(cb.circuitBreakerInstance) == 0L
    }

    def "circuit breaker should close after reset timeout"() {
        given:
        def resetTimeoutMs = 100 // Short timeout for testing
        def cb = setupCircuitBreaker(3, resetTimeoutMs)

        when: "Open the circuit"
        for (int i = 0; i < 3; i++) {
            cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)
        }

        then: "Circuit should be open"
        cb.isOpenMethod.invoke(cb.circuitBreakerInstance)

        when: "Wait for reset timeout"
        Thread.sleep(resetTimeoutMs + 50) // Add buffer time

        then: "Circuit should auto-close on next check"
        !cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() == 0
    }

    def "reset method should clear failure count and time"() {
        given:
        def cb = setupCircuitBreaker(3, 30000)

        when: "Open the circuit"
        for (int i = 0; i < 3; i++) {
            cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)
        }

        then: "Circuit should be open"
        cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() > 0
        cb.openStateTimeField.get(cb.circuitBreakerInstance) > 0L

        when: "Reset is called"
        cb.resetMethod.invoke(cb.circuitBreakerInstance)

        then: "Circuit should be reset"
        !cb.isOpenMethod.invoke(cb.circuitBreakerInstance)
        cb.failureCountField.get(cb.circuitBreakerInstance).get() == 0
        cb.openStateTimeField.get(cb.circuitBreakerInstance) == 0L
    }

    @Unroll
    def "circuit breaker with threshold #threshold should open after exactly #threshold failures"() {
        given:
        def cb = setupCircuitBreaker(threshold, 30000)

        when:
        for (int i = 0; i < threshold - 1; i++) {
            cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)
        }

        then: "Circuit should be closed"
        !cb.isOpenMethod.invoke(cb.circuitBreakerInstance)

        when: "One more failure"
        cb.recordFailureMethod.invoke(cb.circuitBreakerInstance)

        then: "Circuit should be open"
        cb.isOpenMethod.invoke(cb.circuitBreakerInstance)

        where:
        threshold << [1, 3, 5, 10]
    }
}

// Integration test that uses the actual GroovyHttpClient class
class GroovyHttpClientCircuitBreakerIntegrationSpec extends Specification {
    def "circuit breaker should prevent calls when open"() {
        given:
        def client = new GroovyHttpClient("http://example.com",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                2, // Very low threshold
                30000)

        and: "A server that always fails"
        def mockServer = Mock(java.net.http.HttpClient)//GroovyMock(java.net.http.HttpClient)
        // Use reflection to replace the httpClient with our mock
        def httpClientField = GroovyHttpClient.class.getDeclaredField("httpClient")
        httpClientField.setAccessible(true)
        httpClientField.set(client, mockServer)

        // Make the mock always throw an exception
        mockServer.sendAsync(_, _) >> {
            CompletableFuture.failedFuture(new IOException("Simulated network failure"))
        }

        when: "We make several calls to trigger the circuit breaker"
        def firstException = null
        def secondException = null

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

        try {
            client.get("/test").join() // This should trigger circuit open
        } catch (Exception e) {
            // Ignore this one
        }

        def circuitOpenException = null
        try {
            client.get("/test").join() // This should immediately fail with circuit open
        } catch (Exception e) {
            circuitOpenException = unwrapCompletionException(e)
        }

        then: "The first failures should be network errors"
        firstException instanceof IOException
        secondException instanceof IOException

        and: "The subsequent call should fail with CircuitOpenException"
        circuitOpenException != null

        // Get CircuitOpenException class by reflection
        def circuitOpenExceptionClass = null
        GroovyHttpClient.class.getDeclaredClasses().each { clazz ->
            if (clazz.simpleName == "CircuitOpenException") {
                circuitOpenExceptionClass = clazz
            }
        }

        circuitOpenExceptionClass.isInstance(circuitOpenException)
        circuitOpenException.message == "Circuit is open"
    }

    // Helper method to unwrap CompletionException
    private Throwable unwrapCompletionException(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.cause != null) {
            return t.cause
        }
        return t
    }
}

