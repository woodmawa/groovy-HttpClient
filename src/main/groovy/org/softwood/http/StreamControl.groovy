package org.softwood.http

import groovy.transform.CompileStatic

/**
 * Passed as the optional second parameter of a lines / sse / ndjson consumer (2.1):
 * <pre>client.sseSync('/events') { SseEvent e, StreamControl c -> if (e.event == 'done') c.stop() }</pre>
 * After stop() returns from the consumer no further items are delivered, the response is cancelled, and the request
 * completes normally with StreamResult.stopped true. Cancelling the future also ends the stream.
 */
@CompileStatic
class StreamControl {
    private volatile boolean stopped = false
    private volatile long items = 0L

    /** End the stream after the current item. */
    void stop() { stopped = true }

    boolean isStopped() { stopped }

    /** Items delivered so far, including the current one. */
    long getItems() { items }

    /** Internal. */
    void itemDelivered() { items++ }
}
