package org.softwood.http.streaming

import groovy.transform.CompileStatic

import javax.net.ssl.SSLSession
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A response whose stream consumer called StreamControl.stop() (2.1). Cancelling the body makes HTTP/2 and HTTP/3
 * fail the exchange with a cause-less "Stream N cancelled" IOException, so the client rebuilds the response from the
 * head it already received: status, headers and version, with an empty body (the items went to the consumer).
 */
@CompileStatic
final class StoppedResponse implements HttpResponse<byte[]> {
    private final HttpRequest request
    private final int status
    private final HttpHeaders headers
    private final HttpClient.Version version

    StoppedResponse(HttpRequest request, HttpResponse.ResponseInfo head) {
        this.request = request
        this.status = head.statusCode()
        this.headers = head.headers()
        this.version = head.version()
    }

    @Override int statusCode() { status }
    @Override HttpRequest request() { request }
    @Override Optional<HttpResponse<byte[]>> previousResponse() { Optional.empty() }
    @Override HttpHeaders headers() { headers }
    @Override byte[] body() { new byte[0] }
    @Override Optional<SSLSession> sslSession() { Optional.empty() }
    @Override URI uri() { request.uri() }
    @Override HttpClient.Version version() { version }
}
