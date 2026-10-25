package org.softwood.http

import groovy.transform.CompileStatic

import java.nio.file.Path

/**
 * The outcome of a streamed request (2.1): a download to a Path or OutputStream, or a line / SSE / NDJSON stream.
 * The body went to its target, not into memory, so this carries what is known about it instead.
 */
@CompileStatic
class StreamResult {
    final int statusCode
    /** Response headers (decoded responses have no Content-Encoding / Content-Length, as on HttpClientResponse). */
    final Map<String, List<String>> headers
    final String contentType
    final HttpVersion httpVersion
    /** The Content-Encoding the server applied, or null. */
    final String contentEncoding
    /** True when the client decoded the body before it reached the target. */
    final boolean decoded
    /** Body bytes delivered to the target (decoded bytes when decoded). */
    final long bytesRead
    /** Lines, events or JSON values delivered to a stream consumer; 0 for downloads. */
    final long items
    /** True when the consumer stopped the stream early (StreamControl.stop()). */
    final boolean stopped
    /** The file written, for a download to a Path; null otherwise. */
    final Path path

    StreamResult(int statusCode, Map<String, List<String>> headers, String contentType, HttpVersion httpVersion,
                 String contentEncoding, boolean decoded, long bytesRead, long items, boolean stopped, Path path) {
        this.statusCode = statusCode
        this.headers = headers == null ? Collections.<String, List<String>>emptyMap() : Collections.unmodifiableMap(headers)
        this.contentType = contentType
        this.httpVersion = httpVersion
        this.contentEncoding = contentEncoding
        this.decoded = decoded
        this.bytesRead = bytesRead
        this.items = items
        this.stopped = stopped
        this.path = path
    }

    String getHeader(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.key.equalsIgnoreCase(name)) return e.value ? e.value.get(0) : null
        }
        null
    }

    @Override
    String toString() {
        "StreamResult(status ${statusCode}, ${bytesRead} bytes" + (items ? ", ${items} items" : '') +
                (stopped ? ', stopped' : '') + (path != null ? ", ${path}" : '') + ')'
    }
}
