package org.softwood.http.it

import org.softwood.http.SseEvent
import org.softwood.http.StreamControl
import org.softwood.http.StreamResult
import org.softwood.http.features.Feature

import java.net.http.HttpTimeoutException
import java.time.Duration

/** 2.1 line / SSE / NDJSON consumers against the real server, on every protocol. */
class LineStreamMatrixSpec extends IntegrationSpec {

    @Feature('stream.lines')
    def "lines arrive in order over #protocol"() {
        given:
        def seen = []

        when:
        def r = client(protocol).linesSync('/lines') { seen << it }

        then:
        seen == ['alpha', 'beta', 'gamma']
        r.items == 3

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.lines')
    def "lines are delivered while the body is still arriving, and stop() ends it early over #protocol"() {
        given:
        def seen = []
        long started = System.nanoTime()

        when: 'twenty lines, 100 ms apart; stop after the second'
        def r = client(protocol).linesSync('/drip?n=20&gapMs=100') { String line, StreamControl c ->
            seen << line
            if (seen.size() == 2) c.stop()
        }
        long ms = (System.nanoTime() - started).intdiv(1_000_000L)

        then:
        seen == ['drip 1', 'drip 2']
        r.stopped
        ms < 1500

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.lines')
    def "idleTimeout fails a stalled stream with HttpTimeoutException over #protocol"() {
        given:
        def seen = []

        when: 'lines 3 s apart against a 500 ms idle timeout'
        client(protocol).linesSync('/drip?n=3&gapMs=3000', { idleTimeout Duration.ofMillis(500) }) { seen << it }

        then:
        thrown(HttpTimeoutException)
        seen == ['drip 1']

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.ndjson')
    def "NDJSON values arrive parsed over #protocol"() {
        given:
        def seen = []

        when:
        client(protocol).ndjsonSync('/ndjson') { seen << it }

        then:
        seen == [[n: 1], [n: 2], [n: 3]]

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.sse')
    def "SSE events arrive by GET and by POST over #protocol"() {
        given:
        List<SseEvent> got = []
        List<SseEvent> posted = []

        when:
        client(protocol).sseSync('/sse') { SseEvent e -> got << e }
        StreamResult r = client(protocol).postSync(StreamResult, '/sse', '{"q":1}') { sse { SseEvent e -> posted << e } }

        then:
        got*.event == ['greeting', 'message', 'message']
        got*.data == ['one', 'two', 'three']
        got*.id == [null, null, '3']
        posted == got
        r.items == 3

        where:
        protocol << Protocol.available()
    }
}
