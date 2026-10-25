package org.softwood.http.auth

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.softwood.http.AuthenticationException

import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * OAuth2 client credentials grant (RFC 6749 section 4.4), 2.1. The token is fetched on first use and cached until
 * expires_in minus a skew (default 60 s); concurrent requests share one in-flight fetch. A 401 from the API
 * invalidates the cached token (the client then refreshes once and re-sends a replayable request once).
 * The token endpoint must be HTTPS, except on a loopback host or when the client allows insecure connections.
 */
@CompileStatic
final class OAuth2ClientCredentials implements Auth {

    /** The oauth2 { } DSL. */
    @CompileStatic
    static final class Config {
        String tokenUrl
        String clientId
        String clientSecret
        List<String> scopes = []
        String clientAuth = 'basic'
        Duration skew = Duration.ofSeconds(60)
        Map<String, String> params = [:]

        void tokenUrl(String url) { tokenUrl = url }
        void clientId(String id) { clientId = id }
        void clientSecret(String secret) { clientSecret = secret }
        void scope(String... s) { scopes.addAll(s) }
        /** 'basic' (client_secret_basic, default) or 'post' (client_secret_post). */
        void clientAuth(String mode) { clientAuth = mode }
        void skew(Duration d) { skew = d }
        /** An extra form parameter for the token request (e.g. audience, resource). */
        void param(String name, String value) { params[name] = value }
    }

    static OAuth2ClientCredentials from(@DelegatesTo(value = Config, strategy = Closure.DELEGATE_FIRST) Closure dsl) {
        def cfg = new Config()
        Closure c = (Closure) dsl.clone()
        c.delegate = cfg
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call(cfg)
        new OAuth2ClientCredentials(cfg)
    }

    private static final class Token {
        final String value
        final long expiresAtNanos   // Long.MAX_VALUE when the server gave no expires_in
        Token(String value, long expiresAtNanos) { this.value = value; this.expiresAtNanos = expiresAtNanos }
        boolean valid() { expiresAtNanos == Long.MAX_VALUE || System.nanoTime() < expiresAtNanos }
    }

    private final URI tokenUri
    private final String clientId
    private final String clientSecret
    private final List<String> scopes
    private final boolean secretInBody
    private final long skewNanos
    private final Map<String, String> params

    private final Object lock = new Object()
    private Token current
    private CompletableFuture<Token> inFlight
    private volatile int fetches = 0

    OAuth2ClientCredentials(Config cfg) {
        if (!cfg.tokenUrl) throw new IllegalArgumentException('oauth2 { } needs tokenUrl')
        if (!cfg.clientId) throw new IllegalArgumentException('oauth2 { } needs clientId')
        if (cfg.clientSecret == null) throw new IllegalArgumentException('oauth2 { } needs clientSecret')
        if (!(cfg.clientAuth in ['basic', 'post'])) throw new IllegalArgumentException("oauth2 clientAuth must be 'basic' or 'post'")
        this.tokenUri = URI.create(cfg.tokenUrl)
        if (!(tokenUri.scheme?.toLowerCase(Locale.ROOT) in ['http', 'https']) || !tokenUri.host) {
            throw new IllegalArgumentException('oauth2 tokenUrl must be an absolute http(s) URL')
        }
        this.clientId = cfg.clientId
        this.clientSecret = cfg.clientSecret
        this.scopes = new ArrayList<String>(cfg.scopes)
        this.secretInBody = cfg.clientAuth == 'post'
        this.skewNanos = (cfg.skew ?: Duration.ZERO).toNanos()
        this.params = new LinkedHashMap<String, String>(cfg.params)
    }

    /** Token requests made so far (diagnostics and tests). */
    int getFetchCount() { fetches }

    URI getTokenUri() { tokenUri }

    @Override
    String getCredentialHeader() { null }

    @Override
    CompletableFuture<Credential> credential(TokenTransport transport) {
        CompletableFuture<Token> pending
        CompletableFuture<Token> mine = null
        synchronized (lock) {
            Token t = current
            if (t != null && t.valid()) return CompletableFuture.completedFuture(bearer(t))
            if (inFlight == null) {
                mine = new CompletableFuture<Token>()
                inFlight = mine
            }
            pending = inFlight
        }
        if (mine != null) startFetch(transport, mine)
        pending.thenApply { Token t -> bearer(t) }
    }

    @Override
    void invalidate(Credential used) {
        synchronized (lock) {
            if (current != null && used != null && used.reveal() == 'Bearer ' + current.value) current = null
        }
    }

    private static Credential bearer(Token t) { Credential.header('Authorization', 'Bearer ' + t.value, true) }

    private void startFetch(TokenTransport transport, CompletableFuture<Token> mine) {
        CompletableFuture<HttpResponse<byte[]>> call
        try {
            checkScheme(transport)
            fetches++
            call = transport.postForm(tokenUri, headers(), form())
        } catch (Throwable e) {
            finish(mine, null, e)
            return
        }
        call.whenComplete { HttpResponse<byte[]> resp, Throwable err ->
            if (err != null) {
                finish(mine, null, new AuthenticationException("OAuth2 token request to ${where()} failed: ${err.class.simpleName}".toString(), 0, null, err))
                return
            }
            try {
                finish(mine, parse(resp), null)
            } catch (Throwable e) {
                finish(mine, null, e)
            }
        }
    }

    private void finish(CompletableFuture<Token> mine, Token token, Throwable error) {
        synchronized (lock) {
            if (inFlight.is(mine)) inFlight = null
            if (token != null) current = token
        }
        if (error != null) mine.completeExceptionally(error) else mine.complete(token)
    }

    private void checkScheme(TokenTransport transport) {
        if ('https'.equalsIgnoreCase(tokenUri.scheme)) return
        String h = AuthPolicy.norm(tokenUri.host)
        boolean loopback = h == 'localhost' || h.startsWith('127.') || h == '::1'
        if (!loopback && !transport.insecureAllowed) {
            throw new AuthenticationException("OAuth2 tokenUrl must be HTTPS (got ${where()}); plain HTTP is allowed only for loopback hosts or when the client allows insecure connections".toString())
        }
    }

    private Map<String, String> headers() {
        if (secretInBody) return Collections.<String, String>emptyMap()
        String pair = enc(clientId) + ':' + enc(clientSecret)
        ['Authorization': 'Basic ' + Base64.encoder.encodeToString(pair.getBytes(StandardCharsets.UTF_8))]
    }

    private String form() {
        Map<String, String> f = new LinkedHashMap<String, String>()
        f['grant_type'] = 'client_credentials'
        if (scopes) f['scope'] = scopes.join(' ')
        f.putAll(params)
        if (secretInBody) {
            f['client_id'] = clientId
            f['client_secret'] = clientSecret
        }
        f.collect { String k, String v -> enc(k) + '=' + enc(v) }.join('&')
    }

    private Token parse(HttpResponse<byte[]> resp) {
        int status = resp.statusCode()
        Map body = null
        try {
            Object parsed = new JsonSlurper().parse(resp.body() ?: new byte[0])
            if (parsed instanceof Map) body = (Map) parsed
        } catch (Exception ignored) {
            // not JSON: reported below without the body
        }
        if (status != 200) {
            String code = body?.get('error') instanceof String ? (String) body.get('error') : null
            throw new AuthenticationException(("OAuth2 token request to ${where()} failed: HTTP ${status}" + (code ? " (${code})" : '')).toString(), status, code)
        }
        Object token = body?.get('access_token')
        if (!(token instanceof String) || !token) {
            throw new AuthenticationException("OAuth2 token response from ${where()} has no access_token".toString(), status)
        }
        Object type = body.get('token_type')
        if (type != null && !'bearer'.equalsIgnoreCase(type.toString())) {
            throw new AuthenticationException("OAuth2 token_type '${type}' from ${where()} is not supported (bearer only)".toString(), status)
        }
        long expiresAt = Long.MAX_VALUE
        Object ei = body.get('expires_in')
        if (ei != null) {
            long secs = ei instanceof Number ? ((Number) ei).longValue() : Long.parseLong(ei.toString().trim())
            expiresAt = System.nanoTime() + Duration.ofSeconds(secs).toNanos() - skewNanos
        }
        new Token((String) token, expiresAt)
    }

    private String where() { tokenUri.scheme + '://' + tokenUri.authority + (tokenUri.rawPath ?: '') }

    private static String enc(String s) { URLEncoder.encode(s, StandardCharsets.UTF_8) }

    @Override
    String toString() { 'oauth2(clientCredentials, tokenUrl=' + where() + ', clientId=' + clientId + ', clientSecret=***)' }
}
