package org.softwood.http.auth

import groovy.transform.CompileStatic

import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * How a token-fetching scheme (OAuth2) reaches its token endpoint (2.1): the client's own HttpClient (TLS, trust
 * store, proxy), without the client's allowedHosts, retry or interceptors. Only the scheme's configured URL is used.
 */
@CompileStatic
interface TokenTransport {
    CompletableFuture<HttpResponse<byte[]>> postForm(URI uri, Map<String, String> headers, String formBody)
}
