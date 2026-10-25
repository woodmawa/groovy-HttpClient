package org.softwood.http

import groovy.transform.CompileStatic

/**
 * A response labelled with a Content-Encoding the client decodes (gzip, deflate) whose body is truncated, corrupt or
 * not in that format (2.1). Not an I/O error: retrying would fetch the same bytes, so retry policies do not retry it.
 */
@CompileStatic
class ContentDecodingException extends RuntimeException {
    ContentDecodingException(String message, Throwable cause = null) {
        super(message, cause)
    }
}
