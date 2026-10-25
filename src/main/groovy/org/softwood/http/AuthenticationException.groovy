package org.softwood.http

import groovy.transform.CompileStatic

/**
 * A token request failed (2.1, OAuth2 client credentials). Carries the token endpoint's status and its RFC 6749
 * {@code error} code when it sent one -- never the response body, the client secret or a token. Not retried.
 */
@CompileStatic
class AuthenticationException extends RuntimeException {
    /** HTTP status from the token endpoint, or 0 when the failure was not an HTTP response. */
    final int statusCode
    /** RFC 6749 section 5.2 error code (invalid_client, invalid_scope, ...), or null. */
    final String error

    AuthenticationException(String message, int statusCode = 0, String error = null, Throwable cause = null) {
        super(message, cause)
        this.statusCode = statusCode
        this.error = error
    }
}
