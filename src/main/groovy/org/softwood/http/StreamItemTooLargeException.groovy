package org.softwood.http

import groovy.transform.CompileStatic

/**
 * A line, SSE event or NDJSON value exceeded SecurityConfig.maxStreamItemBytes (2.1). That limit bounds what a stream
 * consumer holds in memory at once; maxResponseBytes still bounds the whole body.
 */
@CompileStatic
class StreamItemTooLargeException extends RuntimeException {
    final long limit

    StreamItemTooLargeException(long limit, String what) {
        super('A stream ' + what + ' exceeded maxStreamItemBytes (' + limit + ')')
        this.limit = limit
    }
}
