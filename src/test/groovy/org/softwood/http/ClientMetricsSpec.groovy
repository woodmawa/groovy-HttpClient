package org.softwood.http

import org.softwood.http.GroovyHttpClient.CircuitOpenException
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.test.MockHttpServer
import org.softwood.http.features.Feature
import spock.lang.Specification

import java.net.http.HttpTimeoutException
import java.time.Duration

@Feature('observability.metrics')
class ClientMetricsSpec extends Specification {

    MockHttpServer server = new MockHttpServer()

    def setup() {
        server.init()
        server.addRequestCheck('GET', '/ok', 200).withResponseBody('fine')
        server.addRequestCheck('GET', '/missing', 404)
        server.addRequestCheck('GET', '/fail', 500)
        server.addRequestCheck('GET', '/slow', 200).withResponseBody('late').withDelay(800)
        server.addRequestCheck('GET', '/lines', 200).withResponseBody('a\nb\nc\n')
    }

    def cleanup() { server.shutdown() }

    private GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}")
                .withOverrides([enableMetrics: true] + overrides))
    }

    def "requests, outcomes, status classes, attempts and retries are counted"() {
        given:
        def c = client()

        when:
        c.getSync('/ok'); c.getSync('/ok')
        try { c.getSync('/missing') } catch (HttpResponseException ignored) {}
        try {
            c.getSync('/fail') { retry { maxAttempts 3; on5xx(true); backoffExponential(5, 10, false) } }
        } catch (HttpResponseException ignored) {}
        def s = c.metrics.snapshot()

        then:
        c.metrics.enabled
        s.requests == 4
        s.succeeded == 2
        s.failed == 2
        s.status2xx == 2
        s.status4xx == 1
        s.status5xx == 1                 // one request, whatever its attempts
        s.attempts == 6                  // 1 + 1 + 1 + 3
        s.retries == 2
        s.failuresByType == [HttpResponseException: 2L]
        s.averageLatency > Duration.ZERO
        s.maxLatency >= s.averageLatency
        s.toMap().requests == 4L

        cleanup:
        c.close()
    }

    def "timeouts and circuit rejections are counted"() {
        given:
        def c = client(failureThreshold: 1, resetTimeoutMs: 60_000L)

        when:
        try { c.getSync('/slow') { timeout Duration.ofMillis(150) } } catch (HttpTimeoutException ignored) {}
        try { c.getSync('/ok') } catch (CircuitOpenException ignored) {}
        def s = c.metrics.snapshot()

        then:
        s.requests == 2
        s.timeouts == 1
        s.circuitRejections == 1
        s.attempts == 1                  // the rejected request sent nothing
        s.failed == 2
        s.failuresByType == [CircuitOpenException: 1L, HttpTimeoutException: 1L]

        cleanup:
        c.close()
    }

    def "streamed requests are counted with their status"() {
        given:
        def c = client()

        when:
        def lines = []
        c.linesSync('/lines') { String l -> lines << l }

        then:
        lines == ['a', 'b', 'c']
        c.metrics.snapshot().requests == 1
        c.metrics.snapshot().status2xx == 1

        cleanup:
        c.close()
    }

    def "reset() zeroes every counter"() {
        given:
        def c = client()
        c.getSync('/ok')

        when:
        c.metrics.reset()
        def s = c.metrics.snapshot()

        then:
        s.requests == 0 && s.succeeded == 0 && s.attempts == 0 && s.status2xx == 0
        s.failuresByType.isEmpty()
        s.maxLatency == Duration.ZERO

        cleanup:
        c.close()
    }

    def "with enableMetrics(false) nothing is counted"() {
        given:
        def c = client(enableMetrics: false)

        when:
        c.getSync('/ok')

        then:
        !c.metrics.enabled
        c.metrics.snapshot().requests == 0
        c.metrics.toString() == 'ClientMetrics[disabled]'

        cleanup:
        c.close()
    }

    def "the builder's default is on, and the testing() profile is off"() {
        expect:
        GroovyHttpClient.builder("http://localhost:${server.port}").build().metrics.enabled
        !new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}")).metrics.enabled
    }
}
