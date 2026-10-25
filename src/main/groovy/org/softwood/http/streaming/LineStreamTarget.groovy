package org.softwood.http.streaming

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.softwood.http.GroovyHttpClient
import org.softwood.http.SseEvent
import org.softwood.http.StreamControl
import org.softwood.http.StreamItemTooLargeException
import org.softwood.http.codec.MediaType

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Delivers a 2xx body to a consumer item by item as it arrives (2.1): text lines, Server-Sent Events, or NDJSON
 * values. Lines split on \n, \r\n or \r at byte level, so only ASCII-compatible charsets are supported (UTF-8 and the
 * ISO / Windows families). Each item is bounded by maxStreamItemBytes. Consumers run in order on one thread at a
 * time; the next bytes are read only after the consumer returns. Internal API: callers use lines {} / sse {} /
 * ndjson {} in a request closure, or the client's lines / sse / ndjson verbs.
 */
@CompileStatic
final class LineStreamTarget extends StreamTarget {

    static enum Kind {
        LINES('line'), SSE('event'), NDJSON('NDJSON value')
        final String item
        Kind(String item) { this.item = item }
    }

    private final Kind kind
    private final Closure consumer
    private final long maxItemBytes
    private final StreamControl control = new StreamControl()
    private volatile boolean delivered = false

    LineStreamTarget(Kind kind, Closure consumer, long maxItemBytes) {
        if (consumer == null) throw new IllegalArgumentException('A stream consumer closure is required')
        if (maxItemBytes <= 0) throw new IllegalArgumentException("maxStreamItemBytes must be positive (was ${maxItemBytes})")
        this.kind = kind
        this.consumer = consumer
        this.maxItemBytes = maxItemBytes
    }

    StreamControl getControl() { control }

    @Override
    protected BodySink newSink(String contentType) {
        new LineSink(this, charsetOf(contentType))
    }

    /** Once an item has reached the consumer the stream cannot be replayed. */
    @Override
    boolean isRetryable() { !delivered }

    boolean isConsumerStopped() { control.stopped }

    long getMaxItemBytes() { maxItemBytes }

    Kind getKind() { kind }

    void deliver(Object item) {
        delivered = true
        addItem()
        control.itemDelivered()
        if (consumer.maximumNumberOfParameters >= 2) consumer.call(item, control)
        else consumer.call(item)
        if (control.stopped) {
            markStopped()
            throw STOP
        }
    }

    private static Charset charsetOf(String contentType) {
        if (!contentType) return StandardCharsets.UTF_8
        try {
            Charset cs = MediaType.parse(contentType)?.charset
            return cs ?: StandardCharsets.UTF_8
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8
        }
    }

    /* ===================================================================== per-attempt sink */

    @CompileStatic
    private static final class LineSink implements StreamTarget.BodySink {
        private final LineStreamTarget owner
        private final Charset charset
        private final ByteArrayOutputStream line = new ByteArrayOutputStream()
        private boolean afterCR = false
        private boolean first = true
        private long lineNo = 0L

        // SSE state (WHATWG event-stream)
        private final StringBuilder data = new StringBuilder()
        private boolean hasData = false
        private String eventType = null
        private String lastEventId = null
        private Long retry = null

        // NDJSON
        private JsonSlurper slurper

        LineSink(LineStreamTarget owner, Charset charset) {
            this.owner = owner
            this.charset = charset
        }

        @Override
        void write(byte[] b, int off, int len) {
            int start = off
            int end = off + len
            for (int i = off; i < end; i++) {
                byte c = b[i]
                if (c == (byte) 10 || c == (byte) 13) {
                    if (c == (byte) 10 && afterCR) {   // the \n of \r\n
                        afterCR = false
                        start = i + 1
                        continue
                    }
                    append(b, start, i - start)
                    endLine()
                    afterCR = (c == (byte) 13)
                    start = i + 1
                } else {
                    afterCR = false
                }
            }
            append(b, start, end - start)
        }

        private void append(byte[] b, int off, int len) {
            if (len <= 0) return
            if (line.size() + len > owner.maxItemBytes) throw new StreamItemTooLargeException(owner.maxItemBytes, owner.kind == Kind.LINES ? 'line' : owner.kind.item)
            line.write(b, off, len)
        }

        private void endLine() {
            String s = new String(line.toByteArray(), charset)
            line.reset()
            if (first) {
                first = false
                if (s.startsWith('\uFEFF')) s = s.substring(1)
            }
            lineNo++
            switch (owner.kind) {
                case Kind.LINES: owner.deliver(s); break
                case Kind.NDJSON: ndjsonLine(s); break
                case Kind.SSE: sseLine(s); break
            }
        }

        @Override
        void complete() {
            if (owner.consumerStopped) return
            // A last line without a terminator is delivered for lines and NDJSON; an unterminated SSE event is dropped.
            if (line.size() > 0 && owner.kind != Kind.SSE) endLine()
        }

        @Override
        void abort() { }

        private void ndjsonLine(String s) {
            if (s.trim().isEmpty()) return
            if (slurper == null) slurper = new JsonSlurper()
            Object value
            try {
                value = slurper.parseText(s)
            } catch (Exception e) {
                throw new GroovyHttpClient.ContentParseException("NDJSON line ${lineNo} is not valid JSON: ${e.message}".toString(), e)
            }
            owner.deliver(value)
        }

        private void sseLine(String s) {
            if (s.isEmpty()) {
                dispatch()
                return
            }
            if (s.startsWith(':')) return
            int colon = s.indexOf(':')
            String field = colon < 0 ? s : s.substring(0, colon)
            String value = colon < 0 ? '' : s.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
            switch (field) {
                case 'event':
                    eventType = value
                    break
                case 'data':
                    data.append(value).append('\n')
                    hasData = true
                    if (data.length() > owner.maxItemBytes) throw new StreamItemTooLargeException(owner.maxItemBytes, 'event')
                    break
                case 'id':
                    if (value.indexOf('\u0000') < 0) lastEventId = value
                    break
                case 'retry':
                    if (value ==~ /\d+/) retry = Long.valueOf(value)
                    break
                default:
                    break
            }
        }

        private void dispatch() {
            if (!hasData) {
                eventType = null
                retry = null
                return
            }
            String d = data.toString()
            if (d.endsWith('\n')) d = d.substring(0, d.length() - 1)
            SseEvent e = new SseEvent(eventType ?: 'message', d, lastEventId ?: null, retry)
            data.setLength(0)
            hasData = false
            eventType = null
            retry = null
            owner.deliver(e)
        }
    }
}
