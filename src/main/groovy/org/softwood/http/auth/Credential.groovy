package org.softwood.http.auth

import groovy.transform.CompileStatic

import java.net.http.HttpRequest
import java.util.function.BiPredicate

/**
 * One credential to put on one attempt (2.1): a header (Authorization, an API-key header) or a query parameter.
 * The caller's own header or parameter of the same name wins. toString() never shows the secret.
 */
@CompileStatic
final class Credential {
    final String headerName
    final String queryName
    private final String secret
    /** True when a 401 should invalidate it and fetch a fresh one (OAuth2). */
    final boolean refreshable

    private Credential(String headerName, String queryName, String secret, boolean refreshable) {
        this.headerName = headerName
        this.queryName = queryName
        this.secret = secret
        this.refreshable = refreshable
    }

    static Credential header(String name, String value, boolean refreshable = false) {
        new Credential(name, null, value, refreshable)
    }

    static Credential query(String name, String value) {
        new Credential(null, name, value, false)
    }

    /** Internal: the secret value. */
    String reveal() { secret }

    HttpRequest applyTo(HttpRequest req) {
        if (headerName != null) {
            if (req.headers().firstValue(headerName).isPresent()) return req
            return HttpRequest.newBuilder(req, { String n, String v -> true } as BiPredicate<String, String>)
                    .header(headerName, secret).build()
        }
        String raw = req.uri().rawQuery
        String key = URLEncoder.encode(queryName, 'UTF-8')
        if (raw != null && raw.split('&').any { String p -> p == key || p.startsWith(key + '=') }) return req
        String pair = key + '=' + URLEncoder.encode(secret, 'UTF-8')
        String s = req.uri().toString()
        int hash = s.indexOf('#')
        String fragment = hash >= 0 ? s.substring(hash) : ''
        String base = hash >= 0 ? s.substring(0, hash) : s
        URI uri = URI.create(base + (raw ? '&' : '?') + pair + fragment)
        HttpRequest.newBuilder(req, { String n, String v -> true } as BiPredicate<String, String>).uri(uri).build()
    }

    @Override
    String toString() {
        headerName != null ? 'Credential(header ' + headerName + ': ***)' : 'Credential(query ' + queryName + '=***)'
    }
}
