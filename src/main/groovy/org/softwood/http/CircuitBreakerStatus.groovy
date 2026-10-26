package org.softwood.http

import groovy.transform.CompileStatic

import java.time.Duration
import java.time.Instant

/**
 * A read-only view of a client's circuit breaker at one moment (2.1): {@code client.circuitBreakerStatus}.
 *
 * <p>The breaker moves from OPEN to HALF_OPEN lazily: once {@link #resetTimeout} has passed since the last
 * failure, the next request becomes the probe. Until a request arrives, the state still reads OPEN;
 * {@link #probeDue} tells you the next request will be let through.
 */
@CompileStatic
final class CircuitBreakerStatus {
    final GroovyHttpClient.State state
    /** Counted failures since the last success (network errors, timeouts and 5xx; never 4xx). */
    final int failureCount
    final int failureThreshold
    final Duration resetTimeout
    /** When the breaker last opened, or null if it is not open. */
    final Instant openedAt
    /** The most recent counted failure, or null if there has been none. */
    final Instant lastFailureAt

    CircuitBreakerStatus(GroovyHttpClient.State state, int failureCount, int failureThreshold, Duration resetTimeout,
                         Instant openedAt, Instant lastFailureAt) {
        this.state = state
        this.failureCount = failureCount
        this.failureThreshold = failureThreshold
        this.resetTimeout = resetTimeout
        this.openedAt = openedAt
        this.lastFailureAt = lastFailureAt
    }

    boolean isOpen() { state == GroovyHttpClient.State.OPEN }

    /** True when the breaker is open but its reset timeout has passed: the next request will be a probe. */
    boolean isProbeDue() {
        state == GroovyHttpClient.State.OPEN && lastFailureAt != null &&
                Duration.between(lastFailureAt, Instant.now()) > resetTimeout
    }

    @Override
    String toString() {
        "CircuitBreakerStatus[state=${state}, failures=${failureCount}/${failureThreshold}, resetTimeout=${resetTimeout}" +
                (openedAt ? ", openedAt=${openedAt}" : '') + ']'
    }
}
