package org.softwood.http.streaming

import groovy.transform.CompileStatic
import org.softwood.http.GroovyHttpClient
import org.softwood.http.StreamResult

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where a streamed 2xx response body goes (2.1). One per request; each attempt (first send, retry) gets a fresh
 * {@link BodySink} from {@link #openAttempt}. Error and redirect bodies never reach a target: they are read into
 * memory, bounded, for HttpResponseException. Internal API.
 */
@CompileStatic
abstract class StreamTarget {

    /** Receives one attempt's (decoded) body bytes. Throwing from write stops the body and fails the request. */
    interface BodySink {
        void write(byte[] bytes, int offset, int length)
        /** The whole body arrived. */
        void complete()
        /** The attempt failed or was abandoned; release what it holds. Must not throw. */
        void abort()
    }

    protected long bytes
    protected long items
    protected boolean stopped
    private BodySink current

    /** A fresh sink for this attempt; any earlier attempt's sink is aborted first. */
    synchronized BodySink openAttempt() {
        current?.abort()
        bytes = 0L
        items = 0L
        stopped = false
        BodySink inner = newSink()
        current = new CountingSink(this, inner)
        current
    }

    protected abstract BodySink newSink()

    /** May the request be retried after the attempt that just ended? */
    abstract boolean isRetryable()

    /** The request succeeded (final 2xx after retries): make the result visible. */
    void commit() {}

    /** The request failed: discard whatever the last attempt left. */
    synchronized void abort() { current?.abort() }

    /** Marks the stream as stopped early by its consumer. */
    void markStopped() { stopped = true }

    void addBytes(long n) { bytes += n }

    void addItem() { items++ }

    Path getPath() { null }

    StreamResult result(GroovyHttpClient.HttpClientResponse r) {
        new StreamResult(r.statusCode, r.headers, r.contentType, r.httpVersion, r.contentEncoding, r.decoded,
                bytes, items, stopped, getPath())
    }

    @CompileStatic
    private static final class CountingSink implements BodySink {
        private final StreamTarget owner
        private final BodySink inner
        CountingSink(StreamTarget owner, BodySink inner) { this.owner = owner; this.inner = inner }
        @Override void write(byte[] b, int off, int len) { owner.addBytes(len); inner.write(b, off, len) }
        @Override void complete() { inner.complete() }
        @Override void abort() { inner.abort() }
    }

    /* ===================================================================== Path: temp file + atomic move (D1) */

    @CompileStatic
    static final class PathTarget extends StreamTarget {
        private final Path target
        private Path temp

        PathTarget(Path target) {
            if (target == null) throw new IllegalArgumentException('download target must not be null')
            this.target = target.toAbsolutePath()
        }

        @Override
        Path getPath() { target }

        @Override
        protected BodySink newSink() {
            Path dir = target.getParent()
            Files.createDirectories(dir)
            Path t = Files.createTempFile(dir, target.getFileName().toString() + '.', '.part')
            temp = t
            new FileSink(t, new BufferedOutputStream(Files.newOutputStream(t), 65_536))
        }

        /** A Path download restarts cleanly in a new temp file, so it may always be retried. */
        @Override
        boolean isRetryable() { true }

        @Override
        synchronized void commit() {
            if (temp == null) return
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
        }
    }

    @CompileStatic
    private static final class FileSink implements BodySink {
        private final Path file
        private final OutputStream out
        FileSink(Path file, OutputStream out) { this.file = file; this.out = out }
        @Override void write(byte[] b, int off, int len) {
            try { out.write(b, off, len) } catch (IOException e) { throw new UncheckedIOException(e) }
        }
        @Override void complete() {
            try { out.close() } catch (IOException e) { throw new UncheckedIOException(e) }
        }
        @Override void abort() {
            try { out.close() } catch (IOException ignored) { }
            try { Files.deleteIfExists(file) } catch (IOException ignored) { }
        }
    }

    /* ===================================================================== OutputStream: the caller owns it */

    @CompileStatic
    static final class OutputStreamTarget extends StreamTarget {
        private final OutputStream out
        private boolean written

        OutputStreamTarget(OutputStream out) {
            if (out == null) throw new IllegalArgumentException('download target must not be null')
            this.out = out
        }

        void markWritten() { written = true }

        @Override
        protected BodySink newSink() { new CallerStreamSink(this, out) }

        /** Bytes already handed to the caller's stream cannot be taken back: no retry after the first one. */
        @Override
        boolean isRetryable() { !written }
    }

    @CompileStatic
    private static final class CallerStreamSink implements BodySink {
        private final OutputStreamTarget owner
        private final OutputStream out
        CallerStreamSink(OutputStreamTarget owner, OutputStream out) { this.owner = owner; this.out = out }
        @Override void write(byte[] b, int off, int len) {
            if (len > 0) owner.markWritten()
            try { out.write(b, off, len) } catch (IOException e) { throw new UncheckedIOException(e) }
        }
        @Override void complete() {
            try { out.flush() } catch (IOException e) { throw new UncheckedIOException(e) }   // the caller closes it
        }
        @Override void abort() { }
    }
}
