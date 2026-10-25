package org.softwood.http

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * One Server-Sent Event, dispatched by the WHATWG event-stream rules (2.1). The client does not reconnect: a caller
 * that wants to resume sends Last-Event-ID itself, using {@link #id}.
 */
@CompileStatic
@EqualsAndHashCode
class SseEvent {
    /** The event type; 'message' when the stream named none. */
    final String event
    /** The data lines joined with '\n'. */
    final String data
    /** The last event id the stream set (it persists across events), or null. */
    final String id
    /** The reconnection time in ms if this event carried a retry field, else null. */
    final Long retry

    SseEvent(String event, String data, String id, Long retry) {
        this.event = event
        this.data = data
        this.id = id
        this.retry = retry
    }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder('SseEvent(').append(event)
        if (id != null) sb.append(', id=').append(id)
        if (retry != null) sb.append(', retry=').append(retry)
        sb.append(', data=').append(data).append(')').toString()
    }
}
