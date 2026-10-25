package org.softwood.http.clientTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.ContentParseException
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.SecurityConfig
import org.softwood.http.SseEvent
import org.softwood.http.StreamControl
import org.softwood.http.StreamItemTooLargeException
import org.softwood.http.StreamResult
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** 2.1 stream consumers: lines, Server-Sent Events and NDJSON, delivered as the body arrives. */
class LineStreamSpec extends Specification {

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0] + overrides))
    }

    static GroovyHttpClient.RetryPolicy retries(int attempts) {
        new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(attempts); onIoExceptions(true); on5xx(true); backoffExponential(1, 1, false)
        }.build()
    }

    void serve(String path, String body, String type = 'text/plain; charset=UTF-8') {
        server.addRequestCheck('GET', path, 200).withResponseBytes(body.getBytes(StandardCharsets.UTF_8))
                .withResponseHeaders(['Content-Type': type])
    }

    // ================================================================ stream.lines

    @Feature('stream.lines')
    def "lines are delivered in order whatever the line ending, including a last line without one"() {
        given:
        serve('/l', 'one\ntwo\r\nthree\rfour\n\nsix')
        def seen = []

        when:
        StreamResult r = client().linesSync('/l') { String line -> seen << line }

        then:
        seen == ['one', 'two', 'three', 'four', '', 'six']
        r.items == 6
        r.bytesRead == 'one\ntwo\r\nthree\rfour\n\nsix'.length()
        !r.stopped
        r.statusCode == 200
    }

    @Feature('stream.lines')
    def "the charset comes from Content-Type"() {
        given:
        server.addRequestCheck('GET', '/latin', 200).withResponseBytes('caf\u00e9\nna\u00efve\n'.getBytes(StandardCharsets.ISO_8859_1))
                .withResponseHeaders(['Content-Type': 'text/plain; charset=ISO-8859-1'])
        def seen = []

        when:
        client().linesSync('/latin') { seen << it }

        then:
        seen == ['caf\u00e9', 'na\u00efve']
    }

    @Feature('stream.lines')
    def "a request closure configures the request and the async form completes with the result"() {
        given:
        def check = server.addRequestCheck('GET', '/l', 200).withResponseBody('a\nb\n')
        def seen = []

        when:
        def r = client().lines('/l', { header 'X-Trace', 't1' }) { seen << it }.get()

        then:
        check.lastRequest.header('X-Trace') == 't1'
        seen == ['a', 'b']
        r.items == 2
    }

    @Feature('stream.lines')
    def "StreamControl.stop() ends the stream early"() {
        given:
        serve('/l', (1..1000).collect { "line ${it}" }.join('\n'))
        def seen = []

        when:
        def r = client().linesSync('/l') { String line, StreamControl c ->
            seen << line
            if (seen.size() == 3) c.stop()
        }

        then:
        seen == ['line 1', 'line 2', 'line 3']
        r.stopped
        r.items == 3
    }

    @Feature('stream.lines')
    def "a consumer that throws fails the request with that exception, and it is not retried"() {
        given:
        def check = server.addRequestCheck('GET', '/l', 200).withResponseBody('a\nb\nc\n')

        when:
        client(retryPolicy: retries(3)).linesSync('/l') { if (it == 'b') throw new IllegalStateException('consumer says no') }

        then:
        def e = thrown(IllegalStateException)
        e.message == 'consumer says no'
        check.received.size() == 1
    }

    @Feature('stream.lines')
    def "a line longer than maxStreamItemBytes fails; the total still obeys maxResponseBytes"() {
        given:
        serve('/long', 'short\n' + ('x' * 100) + '\n')
        serve('/many', ('0123456789\n' * 100))

        when:
        client(maxStreamItemBytes: 50L).linesSync('/long') { }

        then:
        def e = thrown(StreamItemTooLargeException)
        e.limit == 50L

        when:
        client(maxResponseBytes: 500L).linesSync('/many') { }

        then:
        thrown(ResponseTooLargeException)
    }

    @Feature('stream.lines')
    def "maxStreamItemBytes must be positive"() {
        when:
        client(maxStreamItemBytes: 0L)

        then:
        thrown(IllegalArgumentException)
    }

    @Feature('stream.lines')
    def "an error status raises HttpResponseException and the consumer never runs"() {
        given:
        server.addRequestCheck('GET', '/err', 503).withResponseBody('busy')
        def calls = 0

        when:
        client(retryPolicy: GroovyHttpClient.RetryPolicy.none()).linesSync('/err') { calls++ }

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 503
        e.responseBody == 'busy'
        calls == 0
    }

    @Feature('stream.lines')
    def "with compression on, lines are read from the decoded body"() {
        given:
        def bos = new ByteArrayOutputStream()
        new GZIPOutputStream(bos).withCloseable { it.write(('row\n' * 5000).bytes) }
        server.addRequestCheck('GET', '/z', 200).withResponseBytes(bos.toByteArray())
                .withResponseHeaders(['Content-Encoding': 'gzip', 'Content-Type': 'text/plain'])
        int n = 0

        when:
        def r = client(compression: true).linesSync('/z') { assert it == 'row'; n++ }

        then:
        n == 5000
        r.decoded
    }

    @Feature('stream.lines')
    def "one stream target per request"() {
        when:
        client().getSync('/x') { lines { }; sse { } }

        then:
        thrown(IllegalStateException)
    }

    // ================================================================ stream.ndjson

    @Feature('stream.ndjson')
    def "NDJSON delivers each line parsed, skipping blank lines"() {
        given:
        serve('/n', '{"n":1}\n\n{"n":2,"tags":["a"]}\r\n[1,2]\n', 'application/x-ndjson')
        def seen = []

        when:
        def r = client().ndjsonSync('/n') { seen << it }

        then:
        seen == [[n: 1], [n: 2, tags: ['a']], [1, 2]]
        r.items == 3
    }

    @Feature('stream.ndjson')
    def "a bad NDJSON line fails with ContentParseException naming its line number"() {
        given:
        serve('/n', '{"n":1}\n{oops\n{"n":3}\n', 'application/x-ndjson')
        def seen = []

        when:
        client().ndjsonSync('/n') { seen << it }

        then:
        def e = thrown(ContentParseException)
        e.message.contains('line 2')
        seen == [[n: 1]]
    }

    // ================================================================ stream.sse

    @Feature('stream.sse')
    def "SSE events follow the WHATWG event-stream rules"() {
        given:
        serve('/e', '\uFEFF: a comment\n' +
                'data: one\n\n' +
                'event: greeting\ndata: multi\ndata:line\n\n' +
                'id: 7\nretry: 1500\ndata: three\n\n' +
                'data\n\n' +
                'data: four\n\n' +
                'event: ignored\n\n' +
                'data: unterminated', 'text/event-stream')
        List<SseEvent> seen = []

        when:
        def r = client().sseSync('/e') { SseEvent e -> seen << e }

        then:
        seen*.event == ['message', 'greeting', 'message', 'message', 'message']
        seen*.data == ['one', 'multi\nline', 'three', '', 'four']
        seen*.id == [null, null, '7', '7', '7']
        seen[2].retry == 1500L
        seen[3].retry == null
        r.items == 5
    }

    @Feature('stream.sse')
    def "SSE can be started by a POST through the typed verb"() {
        given:
        def check = server.addRequestCheck('POST', '/chat', 200)
                .withResponseBytes('data: hel\n\ndata: lo\n\nevent: done\ndata: [DONE]\n\n'.bytes)
                .withResponseHeaders(['Content-Type': 'text/event-stream'])
        def text = new StringBuilder()

        when:
        StreamResult r = client().postSync(StreamResult, '/chat', '{"prompt":"hi"}') {
            sse { SseEvent e, StreamControl c ->
                if (e.event == 'done') c.stop() else text << e.data
            }
        }

        then:
        check.lastRequest.bodyText == '{"prompt":"hi"}'
        text.toString() == 'hello'
        r.stopped
        r.items == 3
    }
}
