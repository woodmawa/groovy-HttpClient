package org.softwood.http

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Per-client request counters (2.1), switched on by {@code enableMetrics} (on by default; off in the
 * {@code testing()} and {@code trustAll()} profiles). Cheap enough to leave on: a handful of {@link LongAdder}s.
 *
 * <p>A <em>request</em> is one verb call. It is counted once, whatever its retries, when its outcome is known --
 * before response handlers run, so a 404 a handler turns into {@code null} still counts as a 4xx failure.
 * Read the counters with {@link #snapshot()}; publish them to Micrometer, Prometheus or a log line from there.
 */
@CompileStatic
final class ClientMetrics {

    /** False when the client was built with {@code enableMetrics(false)}: every counter stays at zero. */
    final boolean enabled

    private final LongAdder requests = new LongAdder()
    private final LongAdder succeeded = new LongAdder()
    private final LongAdder failed = new LongAdder()
    private final LongAdder attempts = new LongAdder()
    private final LongAdder retries = new LongAdder()
    private final LongAdder circuitRejections = new LongAdder()
    private final LongAdder timeouts = new LongAdder()
    private final LongAdder[] statusClasses = [new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()] as LongAdder[]
    private final LongAdder completed = new LongAdder()
    private final LongAdder totalNanos = new LongAdder()
    private final AtomicLong maxNanos = new AtomicLong()
    private final Map<String, LongAdder> failuresByType = new ConcurrentHashMap<>()

    ClientMetrics(boolean enabled) {
        this.enabled = enabled
    }

    /* ----------------------------------------------------------------- recording (called by the client) */

    @PackageScope
    void requestStarted() { if (enabled) requests.increment() }

    @PackageScope
    void attemptStarted() { if (enabled) attempts.increment() }

    @PackageScope
    void retryScheduled() { if (enabled) retries.increment() }

    @PackageScope
    void rejectedByCircuit() {
        if (!enabled) return
        circuitRejections.increment()
        failed.increment()
        countFailure(GroovyHttpClient.CircuitOpenException.simpleName)
    }

    /** One finished request: {@code status} is the final status if known (else 0), {@code error} null on success. */
    @PackageScope
    void requestFinished(long nanos, int status, Throwable error) {
        if (!enabled) return
        completed.increment()
        totalNanos.add(nanos)
        maxNanos.accumulateAndGet(nanos) { long a, long b -> Math.max(a, b) }
        if (status >= 100 && status < 600) statusClasses[(int) (status / 100) - 1].increment()
        if (error == null) {
            succeeded.increment()
        } else {
            failed.increment()
            if (error instanceof HttpTimeoutException) timeouts.increment()
            countFailure(error.getClass().simpleName)
        }
    }

    private void countFailure(String type) {
        failuresByType.computeIfAbsent(type) { new LongAdder() }.increment()
    }

    /* ----------------------------------------------------------------- reading */

    /** A consistent-enough copy of every counter (each value is read once; concurrent requests may straddle it). */
    Snapshot snapshot() {
        long done = completed.sum()
        Map<String, Long> byType = new TreeMap<>()
        failuresByType.each { String k, LongAdder v -> byType.put(k, v.sum()) }
        new Snapshot(
                requests.sum(), succeeded.sum(), failed.sum(), attempts.sum(), retries.sum(),
                circuitRejections.sum(), timeouts.sum(),
                statusClasses[0].sum(), statusClasses[1].sum(), statusClasses[2].sum(),
                statusClasses[3].sum(), statusClasses[4].sum(),
                Collections.unmodifiableMap(byType),
                Duration.ofNanos(done == 0 ? 0L : (long) (totalNanos.sum() / done)),
                Duration.ofNanos(maxNanos.get()),
                Duration.ofNanos(totalNanos.sum()))
    }

    /** Sets every counter back to zero (for example after publishing a snapshot as deltas). */
    void reset() {
        [requests, succeeded, failed, attempts, retries, circuitRejections, timeouts, completed, totalNanos].each { LongAdder a -> a.reset() }
        statusClasses.each { LongAdder a -> a.reset() }
        maxNanos.set(0L)
        failuresByType.clear()
    }

    @Override
    String toString() { enabled ? "ClientMetrics${snapshot().toMap()}" : 'ClientMetrics[disabled]' }

    /** Immutable counter values at one moment. */
    @CompileStatic
    static final class Snapshot {
        /** Verb calls, including those the circuit breaker rejected. */
        final long requests
        /** Requests that completed with a status below 400. */
        final long succeeded
        /** Requests that ended in an exception: status 400+, network error, timeout, circuit open... */
        final long failed
        /** Attempts sent (requests plus retries; rejected requests make none). */
        final long attempts
        final long retries
        final long circuitRejections
        final long timeouts
        final long status1xx, status2xx, status3xx, status4xx, status5xx
        /** Failed requests by exception simple name, e.g. {@code HttpResponseException}, {@code ConnectException}. */
        final Map<String, Long> failuresByType
        /** Mean and maximum time from the call to its outcome, over requests that were sent. */
        final Duration averageLatency
        final Duration maxLatency
        final Duration totalLatency

        Snapshot(long requests, long succeeded, long failed, long attempts, long retries, long circuitRejections,
                 long timeouts, long s1, long s2, long s3, long s4, long s5, Map<String, Long> failuresByType,
                 Duration averageLatency, Duration maxLatency, Duration totalLatency) {
            this.requests = requests; this.succeeded = succeeded; this.failed = failed
            this.attempts = attempts; this.retries = retries; this.circuitRejections = circuitRejections
            this.timeouts = timeouts
            this.status1xx = s1; this.status2xx = s2; this.status3xx = s3; this.status4xx = s4; this.status5xx = s5
            this.failuresByType = failuresByType
            this.averageLatency = averageLatency; this.maxLatency = maxLatency; this.totalLatency = totalLatency
        }

        /** The values as a flat map, handy for logging or for registering gauges. */
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>()
            m.requests = requests; m.succeeded = succeeded; m.failed = failed; m.attempts = attempts
            m.retries = retries; m.circuitRejections = circuitRejections; m.timeouts = timeouts
            m.status1xx = status1xx; m.status2xx = status2xx; m.status3xx = status3xx
            m.status4xx = status4xx; m.status5xx = status5xx
            m.averageLatencyMs = averageLatency.toMillis(); m.maxLatencyMs = maxLatency.toMillis()
            m.failuresByType = failuresByType
            m
        }

        @Override
        String toString() { "Snapshot${toMap()}" }
    }
}
