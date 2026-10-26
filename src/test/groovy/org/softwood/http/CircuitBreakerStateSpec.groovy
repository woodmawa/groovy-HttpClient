package org.softwood.http

import org.softwood.http.GroovyHttpClient.CircuitOpenException
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.State
import org.softwood.test.MockHttpServer
import org.softwood.http.features.Feature
import spock.lang.Specification

import java.time.Duration

@Feature('resilience.circuit-inspection')
class CircuitBreakerStateSpec extends Specification {

    MockHttpServer server = new MockHttpServer()
    GroovyHttpClient client
    List<List<State>> events = []

    def setup() {
        server.init()
        server.addRequestCheck('GET', '/fail', 500)
        server.addRequestCheck('GET', '/missing', 404)
        server.addRequestCheck('GET', '/ok', 200).withResponseBody('fine')
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}")
                .withOverrides(failureThreshold: 2, resetTimeoutMs: 200L))
        client.onCircuitStateChange { State from, State to -> events << [from, to] }
    }

    def cleanup() {
        client?.close()
        server.shutdown()
    }

    def "a new client is closed with no failures"() {
        expect:
        client.circuitState == State.CLOSED
        with(client.circuitBreakerStatus) {
            state == State.CLOSED
            failureCount == 0
            failureThreshold == 2
            resetTimeout == Duration.ofMillis(200)
            openedAt == null
            lastFailureAt == null
            !open
            !probeDue
        }
    }

    def "counted failures open the breaker, and the listener sees it"() {
        when:
        2.times { try { client.getSync('/fail') } catch (HttpResponseException ignored) {} }

        then:
        client.circuitState == State.OPEN
        with(client.circuitBreakerStatus) {
            open
            failureCount == 2
            openedAt != null
            lastFailureAt != null
        }
        events == [[State.CLOSED, State.OPEN]]

        when:
        client.getSync('/ok')

        then:
        thrown(CircuitOpenException)
    }

    def "after the reset timeout a probe is due, and a successful probe closes the breaker"() {
        given:
        2.times { try { client.getSync('/fail') } catch (HttpResponseException ignored) {} }

        when:
        Thread.sleep(350)

        then:
        client.circuitState == State.OPEN          // lazy: no request yet
        client.circuitBreakerStatus.probeDue

        when:
        def r = client.getSync('/ok')

        then:
        r.body == 'fine'
        client.circuitState == State.CLOSED
        client.circuitBreakerStatus.failureCount == 0
        events == [[State.CLOSED, State.OPEN], [State.OPEN, State.HALF_OPEN], [State.HALF_OPEN, State.CLOSED]]
    }

    def "a failed probe opens the breaker again"() {
        given:
        2.times { try { client.getSync('/fail') } catch (HttpResponseException ignored) {} }
        Thread.sleep(350)

        when:
        client.getSync('/fail')

        then:
        thrown(HttpResponseException)
        client.circuitState == State.OPEN
        events == [[State.CLOSED, State.OPEN], [State.OPEN, State.HALF_OPEN], [State.HALF_OPEN, State.OPEN]]
    }

    def "4xx responses are answers, not failures"() {
        when:
        3.times { try { client.getSync('/missing') } catch (HttpResponseException ignored) {} }

        then:
        client.circuitState == State.CLOSED
        client.circuitBreakerStatus.failureCount == 0
        events.empty
    }

    def "a listener that throws does not break the client"() {
        given:
        client.onCircuitStateChange { State from, State to -> throw new IllegalStateException('listener bug') }

        when:
        2.times { try { client.getSync('/fail') } catch (HttpResponseException ignored) {} }

        then:
        client.circuitState == State.OPEN
        events == [[State.CLOSED, State.OPEN]]       // the other listener still ran
    }
}
