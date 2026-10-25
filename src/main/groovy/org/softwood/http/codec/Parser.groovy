package org.softwood.http.codec

import groovy.transform.CompileStatic

/**
 * Turns response bytes into an object. {@code target} is {@code Object.class} for an untyped parse
 * ({@code response.parsed}) and the caller's class for a typed one ({@code response.bodyAs(Customer)}).
 * The bytes are already bounded by maxResponseBytes (and decompressed, once compression lands).
 */
@CompileStatic
@FunctionalInterface
interface Parser {
    Object parse(byte[] body, MediaType type, Class<?> target)
}
