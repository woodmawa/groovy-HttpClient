package org.softwood.http.codec

import groovy.transform.CompileStatic

/**
 * Turns a request body object into bytes for a media type. {@code type} is the request's Content-Type as
 * sent (its charset parameter, when present, is the one to encode with).
 */
@CompileStatic
@FunctionalInterface
interface Encoder {
    byte[] encode(Object body, MediaType type)
}
