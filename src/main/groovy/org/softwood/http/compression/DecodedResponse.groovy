package org.softwood.http.compression

import groovy.transform.CompileStatic

import javax.net.ssl.SSLSession
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.function.BiPredicate

/**
 * A response whose body the client decoded (2.1). Its headers no longer describe the bytes as received, so
 * Content-Encoding and Content-Length are dropped; the original coding stays available as {@link #contentEncoding}.
 */
@CompileStatic
final class DecodedResponse implements HttpResponse<byte[]> {
    final HttpResponse<byte[]> delegate
    /** The Content-Encoding the server applied, as it sent it (e.g. 'gzip'). */
    final String contentEncoding
    private final HttpHeaders decodedHeaders

    DecodedResponse(HttpResponse<byte[]> delegate, String contentEncoding) {
        this.delegate = delegate
        this.contentEncoding = contentEncoding
        this.decodedHeaders = HttpHeaders.of(delegate.headers().map(), { String name, String value ->
            !(name.equalsIgnoreCase('Content-Encoding') || name.equalsIgnoreCase('Content-Length'))
        } as BiPredicate<String, String>)
    }

    @Override int statusCode() { delegate.statusCode() }
    @Override HttpRequest request() { delegate.request() }
    @Override Optional<HttpResponse<byte[]>> previousResponse() { delegate.previousResponse() }
    @Override HttpHeaders headers() { decodedHeaders }
    @Override byte[] body() { delegate.body() }
    @Override Optional<SSLSession> sslSession() { delegate.sslSession() }
    @Override URI uri() { delegate.uri() }
    @Override HttpClient.Version version() { delegate.version() }
}
