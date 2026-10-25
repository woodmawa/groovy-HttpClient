package org.softwood.http

import groovy.util.logging.Slf4j

import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.time.Duration

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * SecurityConfig -- immutable configuration for TLS, host policy (SSRF protection), redirects,
 * timeouts, cookies, response-size limits and resilience used by {@link GroovyHttpClient}.
 *
 * <h2>Profiles</h2>
 * <ul>
 *   <li>{@link #defaults(String)} -- what {@code new GroovyHttpClient(baseUrl)} uses. Certificates and host
 *       names verified, TLS 1.3 and 1.2, absolute URLs off, redirects followed only to allowed hosts,
 *       no automatic retries.</li>
 *   <li>{@link #production(String)} -- as defaults, plus TLS 1.3 only, tighter timeouts and retries of
 *       idempotent requests on I/O errors, timeouts and 5xx.</li>
 *   <li>{@link #staging(String)} -- production with logging and a longer request timeout.</li>
 *   <li>{@link #testing(String, SSLContext)} -- for tests against local servers: absolute URLs and all
 *       cookies accepted, no retries. Certificates are STILL verified -- pass an {@link SSLContext} built
 *       from your test trust store to talk to a self-signed test server.</li>
 *   <li>{@link #trustAll(String)} -- disables certificate and host-name verification. Local development
 *       only. This is the only profile that does.</li>
 * </ul>
 *
 * <h2>Custom trust and client certificates</h2>
 * Verified TLS against your own trust material: {@code trustStore} (a KeyStore of trusted certificates),
 * or {@code trustManagers}; mutual TLS with {@code keyManagers}; or a complete {@code sslContext}.
 *
 * <h2>Overrides</h2>
 * Any profile can be adjusted with {@link #withOverrides(Map)}. An explicitly supplied value always wins,
 * including {@code false} and {@code 0} -- e.g. {@code withOverrides(allowRedirects: false)}. Changing
 * {@code baseUrl} without supplying {@code allowedHosts} re-derives the allow-list from the new base URL.
 *
 * @author Will Woodman / Softwood Consulting Ltd
 * @since 1.0.0 (profiles reworked in 2.0.0)
 */
@Slf4j
class SecurityConfig {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10)
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30)
    static final long DEFAULT_MAX_RESPONSE_BYTES  = 10_000_000L
    static final int DEFAULT_MAX_REDIRECTS        = 5

    // Core
    final String baseUrl
    final Duration connectTimeout
    final Duration requestTimeout
    final boolean insecureAllowed
    final boolean allowAbsoluteUrls
    final long maxResponseBytes
    final CookiePolicy cookiePolicy
    final Set<String> allowedHosts
    final boolean enableLogging
    final boolean enableMetrics
    final boolean allowRedirects
    final int maxRedirects
    /**
     * Characters of the response body appended to HttpResponseException messages (2.1). Default 0: messages carry
     * no body, because messages end up in logs (SEC-11). {@link #testing} uses 120. Range 0..4096.
     */
    final int errorBodyPreviewChars
    /**
     * Response compression (2.1, default off): send Accept-Encoding gzip, deflate and decode those encodings.
     * maxResponseBytes then counts decoded bytes. Per request: compression(boolean).
     */
    final boolean compression

    // Protocol
    /** Preferred HTTP version (default HTTP_2). HTTP_3 needs JDK 26+ at runtime; otherwise the client falls back to HTTP_2. */
    final HttpVersion httpVersion
    /** HTTP/3 discovery mode; null = JDK default. Setting it implies httpVersion HTTP_3. */
    final Http3Discovery http3Discovery

    // Resilience
    final int failureThreshold
    final long resetTimeoutMs
    final GroovyHttpClient.RetryPolicy retryPolicy

    // TLS
    final boolean enforceHostnameVerification
    final List<String> allowedTlsProtocols
    final List<String> allowedCipherSuites
    /** SSLContext used for verified TLS: the one supplied, or one built from trustStore / trustManagers / keyManagers. Null = JVM default. */
    final SSLContext sslContext
    /** Trusted certificates (private CA, self-signed test server). */
    final KeyStore trustStore
    final TrustManager[] trustManagers
    /** Client certificate(s) for mutual TLS. */
    final KeyManager[] keyManagers
    private final SSLContext suppliedSslContext

    /** Every setting name the constructor accepts; anything else is refused (SEC-13). */
    static final Set<String> SETTINGS = ([
            'baseUrl', 'connectTimeout', 'requestTimeout', 'insecureAllowed', 'allowAbsoluteUrls', 'maxResponseBytes',
            'cookiePolicy', 'allowedHosts', 'enableLogging', 'enableMetrics', 'allowRedirects', 'maxRedirects',
            'failureThreshold', 'resetTimeoutMs', 'retryPolicy', 'enforceHostnameVerification', 'allowedTlsProtocols',
            'allowedCipherSuites', 'sslContext', 'trustStore', 'trustManagers', 'keyManagers',
            'httpVersion', 'http3Discovery', 'errorBodyPreviewChars', 'compression'
    ] as LinkedHashSet<String>).asImmutable()

    /**
     * Map-based constructor used by the profiles, {@link GroovyHttpClient.Builder} and {@link #withOverrides}.
     * A key that is present with a non-null value is honoured as given; absent or null means the default.
     * (Before 2.0.0 Groovy truth was used, so an explicit {@code false} or {@code 0} silently became the default.)
     */
    protected SecurityConfig(Map args) {
        Map a = args ?: [:]
        Set<String> unknown = (a.keySet().collect { it as String } as Set<String>) - SETTINGS
        if (unknown) {
            // Before 2.0.0 unknown keys were silently ignored -- which is how documented settings such as
            // trustStore / keyManagers never took effect. A misspelt security setting must fail loudly.
            throw new IllegalArgumentException("Unknown SecurityConfig setting(s): ${unknown.sort()}. Valid: ${SETTINGS}")
        }
        this.baseUrl                     = a.baseUrl as String
        if (!baseUrl || !(baseUrl.startsWith('http://') || baseUrl.startsWith('https://'))) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL: ${baseUrl}")
        }
        this.connectTimeout              = opt(a, 'connectTimeout', DEFAULT_CONNECT_TIMEOUT) as Duration
        this.requestTimeout              = opt(a, 'requestTimeout', DEFAULT_REQUEST_TIMEOUT) as Duration
        this.insecureAllowed             = opt(a, 'insecureAllowed', false) as boolean
        this.allowAbsoluteUrls           = opt(a, 'allowAbsoluteUrls', false) as boolean
        this.maxResponseBytes            = opt(a, 'maxResponseBytes', DEFAULT_MAX_RESPONSE_BYTES) as long
        this.cookiePolicy                = opt(a, 'cookiePolicy', CookiePolicy.ACCEPT_ORIGINAL_SERVER) as CookiePolicy
        this.allowedHosts                = Collections.unmodifiableSet(
                ((opt(a, 'allowedHosts', [URI.create(baseUrl).host]) as Collection<String>)
                        .collect { normalizeHost(it) }.findAll { it } as LinkedHashSet<String>))
        this.enableLogging               = opt(a, 'enableLogging', false) as boolean
        this.enableMetrics               = opt(a, 'enableMetrics', true) as boolean
        this.allowRedirects              = opt(a, 'allowRedirects', true) as boolean
        this.maxRedirects                = opt(a, 'maxRedirects', DEFAULT_MAX_REDIRECTS) as int
        this.errorBodyPreviewChars       = opt(a, 'errorBodyPreviewChars', 0) as int
        this.compression                 = opt(a, 'compression', false) as boolean
        this.http3Discovery              = toEnum(Http3Discovery, opt(a, 'http3Discovery', null))
        this.httpVersion                 = toEnum(HttpVersion, opt(a, 'httpVersion',
                http3Discovery != null ? HttpVersion.HTTP_3 : HttpVersion.HTTP_2))

        this.failureThreshold            = opt(a, 'failureThreshold', GroovyHttpClient.DEFAULT_FAILURE_THRESHOLD) as int
        this.resetTimeoutMs              = opt(a, 'resetTimeoutMs', GroovyHttpClient.DEFAULT_RESET_TIMEOUT_MS) as long
        this.retryPolicy                 = opt(a, 'retryPolicy', GroovyHttpClient.RetryPolicy.none()) as GroovyHttpClient.RetryPolicy

        this.enforceHostnameVerification = opt(a, 'enforceHostnameVerification', true) as boolean
        this.allowedTlsProtocols         = Collections.unmodifiableList(
                (opt(a, 'allowedTlsProtocols', ['TLSv1.3', 'TLSv1.2']) as List<String>).collect { it as String })
        this.allowedCipherSuites         = Collections.unmodifiableList(
                (opt(a, 'allowedCipherSuites', []) as List<String>).collect { it as String })
        this.suppliedSslContext          = opt(a, 'sslContext', null) as SSLContext
        this.trustStore                  = opt(a, 'trustStore', null) as KeyStore
        this.trustManagers               = opt(a, 'trustManagers', null) as TrustManager[]
        this.keyManagers                 = opt(a, 'keyManagers', null) as KeyManager[]

        boolean customMaterial = trustStore != null || trustManagers != null || keyManagers != null
        if (suppliedSslContext != null && customMaterial) {
            throw new IllegalArgumentException("sslContext cannot be combined with trustStore / trustManagers / keyManagers")
        }
        if (trustStore != null && trustManagers != null) {
            throw new IllegalArgumentException("Supply trustStore or trustManagers, not both")
        }
        if (insecureAllowed && (suppliedSslContext != null || customMaterial)) {
            throw new IllegalArgumentException("insecureAllowed cannot be combined with sslContext / trustStore / trustManagers / keyManagers")
        }
        this.sslContext = suppliedSslContext ?: (customMaterial ? buildSslContext(trustStore, trustManagers, keyManagers) : null)

        if (http3Discovery != null && httpVersion != HttpVersion.HTTP_3) {
            throw new IllegalArgumentException("http3Discovery requires httpVersion HTTP_3 (was ${httpVersion})")
        }
        if (httpVersion == HttpVersion.HTTP_3 && !allowedTlsProtocols.contains('TLSv1.3')) {
            throw new IllegalArgumentException("HTTP/3 (QUIC) requires TLSv1.3 in allowedTlsProtocols: ${allowedTlsProtocols}")
        }
        if (maxResponseBytes <= 0) throw new IllegalArgumentException("maxResponseBytes must be > 0 (use Long.MAX_VALUE for no limit)")
        if (maxRedirects < 0) throw new IllegalArgumentException("maxRedirects must be >= 0")
        if (errorBodyPreviewChars < 0 || errorBodyPreviewChars > GroovyHttpClient.HttpResponseException.MAX_PREVIEW_CHARS) {
            throw new IllegalArgumentException("errorBodyPreviewChars must be 0..${GroovyHttpClient.HttpResponseException.MAX_PREVIEW_CHARS} (was ${errorBodyPreviewChars})")
        }
    }

    private static SSLContext buildSslContext(KeyStore trustStore, TrustManager[] trustManagers, KeyManager[] keyManagers) {
        TrustManager[] tms = trustManagers
        if (tms == null && trustStore != null) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)
            tms = tmf.trustManagers
        }
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(keyManagers, tms, null)   // null trust managers = JVM default trust (e.g. mTLS to a public-CA server)
        ctx
    }

    /** Accepts the enum itself or a name such as 'HTTP_3', 'http/3', 'HTTP/1.1', 'alt_svc'. */
    private static <E extends Enum<E>> E toEnum(Class<E> type, Object value) {
        if (value == null) return null
        if (type.isInstance(value)) return (E) value
        String n = value.toString().trim().toUpperCase(Locale.ROOT).replace('/', '_').replace('.', '_').replace('-', '_')
        try {
            return Enum.valueOf(type, n)
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid ${type.simpleName}: '${value}'. Valid: ${type.enumConstants*.name()}")
        }
    }

    private static Object opt(Map a, String key, Object dflt) {
        (a.containsKey(key) && a[key] != null) ? a[key] : dflt
    }

    static String normalizeHost(String h) {
        if (!h) return null
        String s = h.trim().toLowerCase(Locale.ROOT)
        while (s.endsWith('.')) s = s[0..-2]
        // URI.getHost() returns IPv6 literals in brackets
        if (s.startsWith('[') && s.endsWith(']')) s = s[1..-2]
        s ?: null
    }

    /** Safe general-purpose defaults; used by {@code new GroovyHttpClient(String)}. */
    static SecurityConfig defaults(String baseUrl) {
        new SecurityConfig([baseUrl: baseUrl])
    }

    static SecurityConfig production(String baseUrl = "https://example.com") {
        new SecurityConfig([
                baseUrl                    : baseUrl,
                insecureAllowed            : false,
                allowAbsoluteUrls          : false,
                cookiePolicy               : CookiePolicy.ACCEPT_ORIGINAL_SERVER,
                enforceHostnameVerification: true,
                connectTimeout             : Duration.ofSeconds(5),
                requestTimeout             : Duration.ofSeconds(20),
                maxResponseBytes           : DEFAULT_MAX_RESPONSE_BYTES,
                allowedTlsProtocols        : ["TLSv1.3"],
                enableLogging              : false,
                enableMetrics              : true,
                retryPolicy                : new GroovyHttpClient.RetryPolicy.Config().tap {
                    maxAttempts(3)
                    backoffExponential(200, 2_000, true)
                    onIoExceptions(true)
                    onTimeouts(true)
                    on5xx(true)
                }.build()
        ])
    }

    static SecurityConfig staging(String baseUrl = "https://staging.example.com") {
        production(baseUrl).withOverrides([
                enableLogging : true,
                requestTimeout: Duration.ofSeconds(30)
        ])
    }

    /**
     * For tests against local / mock servers. Certificates are verified: pass an SSLContext built from
     * the test trust store to reach a self-signed HTTPS test server, or use {@link #trustAll(String)}.
     */
    static SecurityConfig testing(String baseUrl = "http://localhost:8080", SSLContext testSslContext = null) {
        new SecurityConfig([
                baseUrl          : baseUrl,
                insecureAllowed  : false,
                sslContext       : testSslContext,
                allowAbsoluteUrls: true,
                cookiePolicy     : CookiePolicy.ACCEPT_ALL,
                enableLogging    : true,
                enableMetrics    : false,
                errorBodyPreviewChars: 120,   // readable test failures; never use in production
                retryPolicy      : new GroovyHttpClient.RetryPolicy.Config().tap {
                    maxAttempts(1) // no retries in tests unless enabled per request
                }.build()
        ])
    }

    /** Disables certificate and host-name verification. Local development only -- never production. */
    static SecurityConfig trustAll(String baseUrl = "http://localhost:8080") {
        new SecurityConfig([
                baseUrl                    : baseUrl,
                insecureAllowed            : true,
                allowAbsoluteUrls          : true,
                cookiePolicy               : CookiePolicy.ACCEPT_ALL,
                enforceHostnameVerification: false,
                allowedTlsProtocols        : ["TLSv1.3", "TLSv1.2"],
                allowedCipherSuites        : [],
                connectTimeout             : Duration.ofSeconds(3),
                requestTimeout             : Duration.ofSeconds(30),
                failureThreshold           : 1,
                resetTimeoutMs             : 1000L,
                enableLogging              : true,
                enableMetrics              : false,
                retryPolicy                : new GroovyHttpClient.RetryPolicy.Config().tap {
                    maxAttempts(2)
                    backoffExponential(50, 250, true)
                    onIoExceptions(true)
                    onTimeouts(true)
                    on5xx(true)
                }.build()
        ])
    }

    /** Every setting as a map, in the shape the constructor accepts. */
    Map<String, Object> toMap() {
        [
                baseUrl                    : baseUrl,
                connectTimeout             : connectTimeout,
                requestTimeout             : requestTimeout,
                insecureAllowed            : insecureAllowed,
                allowAbsoluteUrls          : allowAbsoluteUrls,
                maxResponseBytes           : maxResponseBytes,
                cookiePolicy               : cookiePolicy,
                allowedHosts               : allowedHosts,
                enableLogging              : enableLogging,
                enableMetrics              : enableMetrics,
                allowRedirects             : allowRedirects,
                maxRedirects               : maxRedirects,
                errorBodyPreviewChars      : errorBodyPreviewChars,
                compression                : compression,
                httpVersion                : httpVersion,
                http3Discovery             : http3Discovery,
                failureThreshold           : failureThreshold,
                resetTimeoutMs             : resetTimeoutMs,
                retryPolicy                : retryPolicy,
                enforceHostnameVerification: enforceHostnameVerification,
                allowedTlsProtocols        : allowedTlsProtocols,
                allowedCipherSuites        : allowedCipherSuites,
                sslContext                 : suppliedSslContext,
                trustStore                 : trustStore,
                trustManagers              : trustManagers,
                keyManagers                : keyManagers
        ] as Map<String, Object>
    }

    /**
     * Copy with overrides. Explicit values win (including false / 0). A new baseUrl without an explicit
     * allowedHosts re-derives the allow-list from the new host rather than keeping the old one.
     */
    SecurityConfig withOverrides(Map overrides) {
        Map<String, Object> merged = toMap()
        if (overrides?.containsKey('baseUrl') && !overrides.containsKey('allowedHosts')) {
            merged.remove('allowedHosts')
        }
        merged.putAll((overrides ?: [:]) as Map<String, Object>)
        // asking for a discovery mode means HTTP/3, even on a profile whose httpVersion is HTTP_2
        if (overrides?.get('http3Discovery') != null && !overrides.containsKey('httpVersion')) {
            merged.httpVersion = HttpVersion.HTTP_3
        }
        new SecurityConfig(merged)
    }
}
