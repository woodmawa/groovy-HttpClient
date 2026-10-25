package org.softwood.http.auth

import groovy.transform.CompileStatic

/**
 * An auth scheme and where it may be sent (2.1). Credentials go to the client's base URL origin only, plus any hosts
 * named with authHosts(...). Built by the auth { } DSL on the builder, the client, or a single request.
 */
@CompileStatic
final class AuthPolicy {
    final Auth auth
    final Set<String> extraHosts

    AuthPolicy(Auth auth, Set<String> extraHosts) {
        this.auth = auth
        this.extraHosts = Collections.unmodifiableSet(new LinkedHashSet<String>(extraHosts ?: Collections.<String>emptySet()))
    }

    /** Same scheme, host and port as the base URL, or a host named in authHosts. */
    boolean appliesTo(URI target, URI base) {
        if (target == null) return false
        String host = norm(target.host)
        if (extraHosts.contains(host)) return true
        base != null && target.scheme?.equalsIgnoreCase(base.scheme) && host == norm(base.host) && port(target) == port(base)
    }

    private static int port(URI u) { u.port != -1 ? u.port : ('https'.equalsIgnoreCase(u.scheme) ? 443 : 80) }

    static String norm(String h) {
        if (h == null) return null
        String s = h.toLowerCase(Locale.ROOT)
        s.startsWith('[') && s.endsWith(']') ? s.substring(1, s.length() - 1) : s
    }

    @Override
    String toString() { "AuthPolicy(${auth}" + (extraHosts ? ", authHosts=${extraHosts}" : '') + ')' }

    /** Build from an auth { } closure. */
    static AuthPolicy from(Closure dsl) {
        def cfg = new Dsl()
        Closure c = (Closure) dsl.clone()
        c.delegate = cfg
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call(cfg)
        cfg.build()
    }

    /** The auth { } DSL: exactly one scheme, optionally authHosts. */
    @CompileStatic
    static final class Dsl {
        private Auth chosen
        private final Set<String> hostsAllowed = new LinkedHashSet<String>()

        void basic(String user, String password) { choose(new Auth.Basic(user, password)) }

        void bearer(String token) { choose(new Auth.Bearer(token)) }

        void bearer(Closure<?> supplier) { choose(new Auth.Bearer(supplier)) }

        /** apiKey(header: 'X-API-Key', value: key) or apiKey(query: 'api_key', value: key). */
        void apiKey(Map<String, ?> args) {
            choose(new Auth.ApiKey(args?.header as String, args?.query as String, args?.value as String))
        }

        /**
         * OAuth2 client credentials: oauth2 { tokenUrl '...'; clientId '...'; clientSecret '...'; scope 'a', 'b' }.
         * Optional: clientAuth 'post', skew Duration, param 'audience', '...'.
         */
        void oauth2(@DelegatesTo(value = OAuth2ClientCredentials.Config, strategy = Closure.DELEGATE_FIRST) Closure dsl) {
            choose(OAuth2ClientCredentials.from(dsl))
        }

        /** Also send credentials to these hosts (beyond the base URL origin). */
        void authHosts(String... hosts) {
            for (String h : hosts) if (h) hostsAllowed.add(norm(h))
        }

        void use(Auth auth) { choose(auth) }

        private void choose(Auth a) {
            if (chosen != null) throw new IllegalStateException('auth { } takes one scheme: basic, bearer, apiKey or oauth2')
            chosen = a
        }

        AuthPolicy build() {
            if (chosen == null) throw new IllegalArgumentException('auth { } needs a scheme: basic, bearer, apiKey or oauth2')
            new AuthPolicy(chosen, hostsAllowed)
        }
    }
}
