package org.softwood.http.compression

import groovy.transform.CompileStatic
import org.softwood.http.ContentDecodingException

import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Incremental decoder for a response Content-Encoding the client advertises: gzip (x-gzip, multi-member) and deflate
 * (zlib-wrapped, or raw deflate as some servers send). Push-based: the body subscriber feeds network buffers as they
 * arrive and receives decoded chunks through a {@link Sink}, so a body is never inflated in full before the size
 * limit can stop it (2.1 zip-bomb guard). Not thread-safe; one instance per response.
 */
@CompileStatic
abstract class ContentDecoder {

    /** Receives decoded bytes. Throwing stops decoding (the size limit does this). */
    interface Sink {
        void accept(byte[] bytes, int offset, int length)
    }

    /** The Accept-Encoding value the client sends when compression is on: exactly what it can decode. */
    static final String ACCEPT_ENCODING = 'gzip, deflate'

    /** A decoder for this Content-Encoding value, or null when the body is to be passed through as received. */
    static ContentDecoder forEncoding(String contentEncoding) {
        String e = contentEncoding?.trim()?.toLowerCase(Locale.ROOT)
        if (e == 'gzip' || e == 'x-gzip') return new GzipDecoder()
        if (e == 'deflate') return new DeflateDecoder()
        null
    }

    protected final byte[] out = new byte[16_384]

    /** Feed the next network buffer. */
    abstract void decode(ByteBuffer input, Sink sink)

    /** The body ended: fail if the compressed stream is incomplete. */
    abstract void finish(Sink sink)

    /** Release native inflater memory; safe to call more than once. */
    abstract void close()

    protected static byte[] toArray(ByteBuffer bb) {
        byte[] a = new byte[bb.remaining()]
        bb.get(a)
        a
    }

    /** Inflate all the inflater can produce from its current input; true when its stream has finished. */
    protected boolean drain(Inflater inflater, Sink sink, CRC32 crc) {
        try {
            while (true) {
                int n = inflater.inflate(out)
                if (n > 0) {
                    if (crc != null) crc.update(out, 0, n)
                    sink.accept(out, 0, n)
                    continue
                }
                if (inflater.finished()) return true
                if (inflater.needsDictionary()) throw new ContentDecodingException('Compressed body needs a preset dictionary')
                return false
            }
        } catch (DataFormatException e) {
            throw new ContentDecodingException("Corrupt compressed body: ${e.message}", e)
        }
        return false
    }

    /* ===================================================================== gzip (RFC 1952) */

    @CompileStatic
    static final class GzipDecoder extends ContentDecoder {
        private static final int FHCRC = 2, FEXTRA = 4, FNAME = 8, FCOMMENT = 16
        private static final int MAX_HEADER = 65_536

        private static enum State { HEADER, BODY, TRAILER }

        private State state = State.HEADER
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream()
        private final CRC32 crc = new CRC32()
        private Inflater inflater
        private int members = 0

        @Override
        void decode(ByteBuffer input, Sink sink) {
            if (!input.hasRemaining()) return
            byte[] data = toArray(input)
            int off = 0
            while (off < data.length) {
                if (state == State.HEADER) {
                    pending.write(data, off, data.length - off)
                    off = data.length
                    byte[] h = pending.toByteArray()
                    if (members > 0 && allZero(h, 0)) { pending.reset(); return }   // padding after the last member
                    if (h.length > MAX_HEADER) throw new ContentDecodingException('gzip header too large')
                    int len = headerLength(h)
                    if (len < 0) return
                    pending.reset()
                    inflater = new Inflater(true)
                    crc.reset()
                    state = State.BODY
                    data = Arrays.copyOfRange(h, len, h.length)
                    off = 0
                } else if (state == State.BODY) {
                    inflater.setInput(data, off, data.length - off)
                    if (!drain(inflater, sink, crc)) return
                    off = data.length - inflater.getRemaining()
                    state = State.TRAILER
                } else {
                    pending.write(data, off, data.length - off)
                    off = data.length
                    byte[] t = pending.toByteArray()
                    if (t.length < 8) return
                    checkTrailer(t)
                    pending.reset()
                    inflater.end()
                    inflater = null
                    members++
                    state = State.HEADER
                    data = Arrays.copyOfRange(t, 8, t.length)
                    off = 0
                }
            }
        }

        @Override
        void finish(Sink sink) {
            boolean clean = state == State.HEADER &&
                    (pending.size() == 0 || (members > 0 && allZero(pending.toByteArray(), 0)))
            close()
            if (!clean) throw new ContentDecodingException('Truncated gzip body')
        }

        @Override
        void close() {
            if (inflater != null) { inflater.end(); inflater = null }
        }

        /** Header length once complete, -1 while more bytes are needed. */
        private static int headerLength(byte[] h) {
            if (h.length >= 1 && (h[0] & 0xff) != 0x1f || h.length >= 2 && (h[1] & 0xff) != 0x8b) {
                throw new ContentDecodingException('Body labelled gzip is not gzip (bad magic bytes)')
            }
            if (h.length < 10) return -1
            if (h[2] != (byte) 8) throw new ContentDecodingException("Unsupported gzip compression method ${h[2]}")
            int flags = h[3] & 0xff
            int p = 10
            if ((flags & FEXTRA) != 0) {
                if (h.length < p + 2) return -1
                p += 2 + ((h[p] & 0xff) | ((h[p + 1] & 0xff) << 8))
                if (h.length < p) return -1
            }
            if ((flags & FNAME) != 0) { p = afterZero(h, p); if (p < 0) return -1 }
            if ((flags & FCOMMENT) != 0) { p = afterZero(h, p); if (p < 0) return -1 }
            if ((flags & FHCRC) != 0) { p += 2; if (h.length < p) return -1 }
            p
        }

        private static int afterZero(byte[] h, int from) {
            for (int i = from; i < h.length; i++) if (h[i] == (byte) 0) return i + 1
            -1
        }

        private void checkTrailer(byte[] t) {
            int expectedCrc = intLE(t, 0)
            int expectedSize = intLE(t, 4)
            if (expectedCrc != (int) crc.getValue()) throw new ContentDecodingException('gzip CRC mismatch')
            if (expectedSize != (int) inflater.getBytesWritten()) throw new ContentDecodingException('gzip length mismatch')
        }

        private static int intLE(byte[] b, int o) {
            (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24)
        }

        private static boolean allZero(byte[] b, int from) {
            for (int i = from; i < b.length; i++) if (b[i] != (byte) 0) return false
            true
        }
    }

    /* ===================================================================== deflate (RFC 1950 zlib, or raw RFC 1951) */

    @CompileStatic
    static final class DeflateDecoder extends ContentDecoder {
        private final ByteArrayOutputStream head = new ByteArrayOutputStream()
        private Inflater inflater
        private boolean raw
        private boolean finished

        @Override
        void decode(ByteBuffer input, Sink sink) {
            if (!input.hasRemaining() || finished) {   // bytes after the end of the stream are ignored
                input.position(input.limit())
                return
            }
            byte[] data = toArray(input)
            if (inflater == null) {
                head.write(data, 0, data.length)
                data = head.toByteArray()
                if (data.length < 2) return
                head.reset()
                raw = !isZlibHeader(data)
                inflater = new Inflater(raw)
            }
            inflater.setInput(data, 0, data.length)
            finished = drain(inflater, sink, null)
        }

        @Override
        void finish(Sink sink) {
            try {
                if (inflater == null) {
                    if (head.size() == 0) return
                    throw new ContentDecodingException('Truncated deflate body')
                }
                if (!finished && raw) {
                    inflater.setInput(new byte[1])   // raw inflate may want one extra byte to signal the end
                    finished = drain(inflater, sink, null)
                }
                if (!finished) throw new ContentDecodingException('Truncated deflate body')
            } finally {
                close()
            }
        }

        @Override
        void close() {
            if (inflater != null) { inflater.end(); inflater = null }
        }

        private static boolean isZlibHeader(byte[] h) {
            int cmf = h[0] & 0xff
            int flg = h[1] & 0xff
            (cmf & 0x0f) == 8 && (cmf >> 4) <= 7 && ((cmf << 8) | flg) % 31 == 0
        }
    }
}
