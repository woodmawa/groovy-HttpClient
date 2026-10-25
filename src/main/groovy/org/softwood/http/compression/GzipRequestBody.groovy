package org.softwood.http.compression

import groovy.transform.CompileStatic

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.function.Supplier
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * gzip request bodies (2.1, gzipBody()). Wraps any BodyPublisher, so it works for every codec, multipart and streamed
 * uploads alike. Compression is streamed: the inner publisher is read through a bounded InputStream and deflated as it
 * is sent, never buffered whole. Each send (a retry, a 307/308 redirect) re-subscribes the inner publisher, so the body
 * is replayable exactly when the inner one is. The result has no known length and is sent chunked (HTTP/1.1).
 */
@CompileStatic
final class GzipRequestBody {

    private GzipRequestBody() {}

    static HttpRequest.BodyPublisher wrap(HttpRequest.BodyPublisher inner) {
        HttpRequest.BodyPublishers.ofInputStream({ ->
            (InputStream) new GzipCompressingInputStream(toInputStream(inner))
        } as Supplier<InputStream>)
    }

    /** A blocking InputStream fed by a publisher, with the JDK's own bounded, demand-driven buffering. */
    static InputStream toInputStream(Flow.Publisher<ByteBuffer> publisher) {
        HttpResponse.BodySubscriber<InputStream> sub = HttpResponse.BodySubscribers.ofInputStream()
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override void onSubscribe(Flow.Subscription s) { sub.onSubscribe(s) }
            @Override void onNext(ByteBuffer item) { sub.onNext(List.of(item)) }
            @Override void onError(Throwable t) { sub.onError(t) }
            @Override void onComplete() { sub.onComplete() }
        })
        sub.getBody().toCompletableFuture().join()
    }

    /** Reads a source stream and yields its gzip (RFC 1952) encoding. */
    @CompileStatic
    static final class GzipCompressingInputStream extends InputStream {
        private static final byte[] HEADER = [0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff] as byte[]

        private final InputStream source
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true)
        private final CRC32 crc = new CRC32()
        private final byte[] inBuf = new byte[8192]
        private final byte[] outBuf = new byte[8192]
        private byte[] chunk = HEADER
        private int pos = 0
        private int lim = HEADER.length
        private boolean sourceDone = false
        private boolean trailerDone = false

        GzipCompressingInputStream(InputStream source) { this.source = source }

        @Override
        int read() {
            byte[] one = new byte[1]
            int n = read(one, 0, 1)
            n < 0 ? -1 : (one[0] & 0xff)
        }

        @Override
        int read(byte[] b, int off, int len) {
            if (len == 0) return 0
            while (pos >= lim) {
                if (!fill()) return -1
            }
            int n = Math.min(len, lim - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            n
        }

        private boolean fill() {
            if (trailerDone) return false
            while (!deflater.finished()) {
                int n = deflater.deflate(outBuf)
                if (n > 0) { set(outBuf, n); return true }
                if (deflater.needsInput()) {
                    int r = source.read(inBuf)
                    if (r < 0) {
                        sourceDone = true
                        deflater.finish()
                    } else if (r > 0) {
                        crc.update(inBuf, 0, r)
                        deflater.setInput(inBuf, 0, r)
                    }
                }
            }
            byte[] t = new byte[8]
            putIntLE(t, 0, (int) crc.getValue())
            putIntLE(t, 4, (int) deflater.getBytesRead())
            deflater.end()
            trailerDone = true
            set(t, 8)
            true
        }

        private void set(byte[] b, int n) { chunk = b; pos = 0; lim = n }

        private static void putIntLE(byte[] b, int o, int v) {
            b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24)
        }

        @Override
        void close() {
            deflater.end()
            source.close()
        }
    }
}
