package org.softwood.http.auth

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * An auth scheme (2.1). credential() is asked once per attempt, so suppliers and token caches see every retry.
 * Implementations must never put the secret in toString(), messages or logs.
 */
@CompileStatic
interface Auth {

    CompletableFuture<Credential> credential(TokenTransport transport)

    /** A 401 came back for a request that carried {@code used}: drop it so the next credential() fetches anew. */
    void invalidate(Credential used)

    /** The custom header this scheme sends, for cross-origin redirect stripping; null for Authorization / query. */
    String getCredentialHeader()

    /* ===================================================================== built-in schemes */

    @CompileStatic
    static final class Basic implements Auth {
        private final String user
        private final Credential credential

        Basic(String user, String password) {
            if (user == null || password == null) throw new IllegalArgumentException('basic auth needs a user and a password')
            if (user.contains(':')) throw new IllegalArgumentException('A basic auth user id must not contain a colon (RFC 7617)')
            this.user = user
            String token = Base64.encoder.encodeToString((user + ':' + password).getBytes(StandardCharsets.UTF_8))
            this.credential = Credential.header('Authorization', 'Basic ' + token)
        }

        @Override CompletableFuture<Credential> credential(TokenTransport transport) { CompletableFuture.completedFuture(credential) }
        @Override void invalidate(Credential used) { }
        @Override String getCredentialHeader() { null }
        @Override String toString() { 'basic(user=' + user + ', password=***)' }
    }

    @CompileStatic
    static final class Bearer implements Auth {
        private final String token
        private final Closure<?> supplier

        Bearer(String token) {
            if (!token) throw new IllegalArgumentException('bearer token must not be empty')
            this.token = token
            this.supplier = null
        }

        /** The supplier is called for every attempt (e.g. a vault lookup or a token another component refreshes). */
        Bearer(Closure<?> supplier) {
            if (supplier == null) throw new IllegalArgumentException('bearer token supplier must not be null')
            this.token = null
            this.supplier = supplier
        }

        @Override
        CompletableFuture<Credential> credential(TokenTransport transport) {
            try {
                String t = token != null ? token : supplier.call()?.toString()
                if (!t) throw new IllegalStateException('The bearer token supplier returned no token')
                return CompletableFuture.completedFuture(Credential.header('Authorization', 'Bearer ' + t))
            } catch (Throwable e) {
                return CompletableFuture.<Credential>failedFuture(e)
            }
        }

        @Override void invalidate(Credential used) { }
        @Override String getCredentialHeader() { null }
        @Override String toString() { supplier != null ? 'bearer(supplier)' : 'bearer(token=***)' }
    }

    @CompileStatic
    static final class ApiKey implements Auth {
        private final String header
        private final String query
        private final Credential credential

        ApiKey(String header, String query, String value) {
            if ((header == null) == (query == null)) throw new IllegalArgumentException('apiKey needs exactly one of header: or query:')
            if (!value) throw new IllegalArgumentException('apiKey value must not be empty')
            this.header = header
            this.query = query
            this.credential = header != null ? Credential.header(header, value) : Credential.query(query, value)
        }

        @Override CompletableFuture<Credential> credential(TokenTransport transport) { CompletableFuture.completedFuture(credential) }
        @Override void invalidate(Credential used) { }
        @Override String getCredentialHeader() { header }
        @Override String toString() { header != null ? 'apiKey(header ' + header + '=***)' : 'apiKey(query ' + query + '=***)' }
    }
}
