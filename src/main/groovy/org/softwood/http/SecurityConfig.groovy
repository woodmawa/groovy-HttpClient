package org.softwood.http

import groovy.util.logging.Slf4j

import java.net.CookiePolicy
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
 * SecurityConfig — Centralized configuration for all runtime security, TLS, URL/host policies,
 * request timeouts, cookie behavior, and risk controls used by {@link org.softwood.http.GroovyHttpClient}.
 *
 * <p>
 * The class provides a declarative, environment-aware way to configure an HTTP client:
 * production, staging, testing, or fully custom modes. It consolidates protocol rules, network
 * validation, body-size limits, virtual-thread behavior, optional "trust-all" insecure testing mode,
 * circuit-breaker tuning, and host allow-list controls (SSRF protection).
 * </p>
 *
 * <h2>Primary Responsibilities</h2>
 * <ul>
 *   <li><strong>TLS and hostname verification policies</strong></li>
 *   <li><strong>Cookie acceptance rules</strong> (via {@link java.net.CookiePolicy})</li>
 *   <li><strong>Allow-listing of target hosts</strong> to prevent SSRF</li>
 *   <li><strong>Absolute URL enable/disable</strong> control</li>
 *   <li><strong>Request and connection timeout control</strong></li>
 *   <li><strong>Maximum response body size</strong> for safety against unbounded downloads</li>
 *   <li><strong>Circuit breaker configuration</strong>: failure thresholds and reset time</li>
 *   <li><strong>Insecure TLS override</strong> for tests (trust-all, disabled hostname verification)</li>
 *   <li><strong>Logging and debugging flags</strong> for staging setups</li>
 * </ul>
 *
 * <h2>Environment Profiles</h2>
 * <p>This class offers ready-made profiles:</p>
 * <ul>
 *   <li>{@code SecurityConfig.production(baseUrl)}
 *     <ul>
 *       <li>TLS enforced</li>
 *       <li>Hostname verification enabled</li>
 *       <li>Absolute URLs disabled (SSRF protection)</li>
 *       <li>CookiePolicy = ACCEPT_ORIGINAL_SERVER</li>
 *       <li>Strict body-size and timeout rules</li>
 *     </ul>
 *   </li>
 *
 *   <li>{@code SecurityConfig.staging(baseUrl)}
 *     <ul>
 *       <li>TLS enforced</li>
 *       <li>More verbose logging</li>
 *       <li>Moderate body-size limit</li>
 *       <li>Absolute URLs allowed if whitelisted</li>
 *     </ul>
 *   </li>
 *
 *   <li>{@code SecurityConfig.testing(baseUrl)}
 *     <ul>
 *       <li>Insecure TLS allowed (trust-all)</li>
 *       <li>Absolute URLs allowed</li>
 *       <li>CookiePolicy = ACCEPT_ALL</li>
 *       <li>Generous timeouts</li>
 *       <li>Host allow-list wide-open for mock/test servers</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Custom Configuration</h2>
 * <p>
 * Any profile may be modified using:
 * </p>
 *
 * <pre>{@code
 * def cfg = SecurityConfig.production("https://api.example.com")
 *              .withOverrides([
 *                  requestTimeout : Duration.ofSeconds(10),
 *                  enableLogging  : true,
 *                  allowAbsoluteUrls: false
 *              ])
 * }</pre>
 *
 * <p>
 * All properties are immutable by default. {@code withOverrides} returns a new, adjusted config instance.
 * </p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><strong>baseUrl</strong> — root URL the client is anchored to</li>
 *   <li><strong>allowAbsoluteUrls</strong> — enable/disable raw absolute URLs</li>
 *   <li><strong>allowedHosts</strong> — list of valid target hosts (SSRF mitigation)</li>
 *   <li><strong>cookiePolicy</strong> — acceptance behavior for cookies</li>
 *   <li><strong>connectTimeout</strong> — handshake timeout</li>
 *   <li><strong>requestTimeout</strong> — per-request timeout</li>
 *   <li><strong>maxResponseBytes</strong> — safety limit for response bodies</li>
 *   <li><strong>failureThreshold</strong> — number of failures before circuit opens</li>
 *   <li><strong>resetTimeoutMs</strong> — cooling-off period for circuit breaker</li>
 *   <li><strong>insecureAllowed</strong> — trust-all mode for test/dev</li>
 *   <li><strong>enableLogging</strong> — request/response visibility</li>
 * </ul>
 *
 * <h2>Interaction with GroovyHttpClient</h2>
 * <p>
 * When a {@link SecurityConfig} is passed to {@link org.softwood.http.GroovyHttpClient#GroovyHttpClient(SecurityConfig)},
 * the client adopts:
 * </p>
 *
 * <ul>
 *   <li>TLS rules and hostname verification</li>
 *   <li>Host allow-list enforcement</li>
 *   <li>Absolute URL rules</li>
 *   <li>Cookie manager behavior</li>
 *   <li>Circuit breaker threshold & reset timing</li>
 *   <li>Response body-size limits</li>
 *   <li>Logging settings</li>
 *   <li>Trust-all SSL override (if configured)</li>
 * </ul>
 *
 * <p>
 * This ensures identical behavior across environments with no code changes.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * def cfg = SecurityConfig.production("https://internal.api")
 *              .withOverrides(enableLogging: true)
 *
 * def client = new GroovyHttpClient(cfg)
 *
 * def resp = client.getSync("/heartbeat")
 * }</pre>
 *
 * <h2>Recommended Production Settings</h2>
 * <ul>
 *   <li>TLSv1.3 only</li>
 *   <li>insecureAllowed = false</li>
 *   <li>allowAbsoluteUrls = false</li>
 *   <li>cookiePolicy = CookiePolicy.ACCEPT_ORIGINAL_SERVER</li>
 *   <li>maxResponseBytes = 10 MB or less</li>
 *   <li>requestTimeout ≤ 30 seconds</li>
 *   <li>connectTimeout ≤ 5 seconds</li>
 * </ul>
 *
 * @author
 *   Will Woodman / Softwood Consulting Ltd
 * @since 1.4.0
 */

/**
 * Centralized security configuration for GroovyHttpClient.
 * Defines safe defaults and environment-specific overrides
 * for production, staging, or local testing.
 *
 * Use this with GroovyHttpClientBuilder:
 *
 *   def cfg = SecurityConfig.production()
 *   def client = new GroovyHttpClientBuilder(cfg.baseUrl)
 *       .connectTimeout(cfg.connectTimeout)
 *       .requestTimeout(cfg.requestTimeout)
 *       .cookiePolicy(cfg.cookiePolicy)
 *       .allowAbsoluteUrls(cfg.allowAbsoluteUrls)
 *       .maxResponseBytes(cfg.maxResponseBytes)
 *       .insecureAllowed(cfg.insecureAllowed)
 *       .build()
 *
 * Provides safe defaults for production, staging, testing, and local development.
 * This class is immutable; helper factory methods enable easy profile selection.
 *
 * Profiles:
 *   - production() : Strict, safe, TLS-only, no insecure connections.
 *   - staging()    : Similar to prod but with logging enabled.
 *   - testing()    : Allows insecure connections, accepts all cookies.
 *   - trustAll()   : For local dev & unit testing; disables all TLS verification.
 *
 * All fields are final and supplied via the internal map-based constructor.
 */
@Slf4j
class SecurityConfig {

    /** -------------------------------
     * Core connection & behavioral settings
     * ------------------------------- */
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

    /** -------------------------------
     * Retry & circuit breaker behavior
     * ------------------------------- */
    final int failureThreshold
    final long resetTimeoutMs

    /** -------------------------------
     * TLS settings
     * ------------------------------- */
    final boolean enforceHostnameVerification
    final List<String> allowedTlsProtocols
    final List<String> allowedCipherSuites

    /**
     * Internal constructor. This class is immutable; use predefined
     * profiles or withOverrides() to generate variants.
     */
    private SecurityConfig(Map args) {
        this.baseUrl                    = args.baseUrl
        this.connectTimeout             = args.connectTimeout ?: Duration.ofSeconds(10)
        this.requestTimeout             = args.requestTimeout ?: Duration.ofSeconds(30)
        this.insecureAllowed            = args.insecureAllowed ?: false
        this.allowAbsoluteUrls          = args.allowAbsoluteUrls ?: false
        this.maxResponseBytes           = args.maxResponseBytes ?: 10_000_000L
        this.cookiePolicy               = args.cookiePolicy ?: CookiePolicy.ACCEPT_ORIGINAL_SERVER
        this.allowedHosts               = args.allowedHosts ?: [URI.create(baseUrl).host] as Set
        this.enableLogging              = args.enableLogging ?: false
        this.enableMetrics              = args.enableMetrics ?: true
        this.allowRedirects             = args.allowRedirects ?: true

        this.failureThreshold           = args.failureThreshold ?: 5
        this.resetTimeoutMs             = args.resetTimeoutMs ?: 30_000L

        this.enforceHostnameVerification = args.enforceHostnameVerification ?: true
        this.allowedTlsProtocols         = args.allowedTlsProtocols ?: ["TLSv1.3"]
        this.allowedCipherSuites         = args.allowedCipherSuites ?: []
    }

    /* ============================================================
     *  Predefined configuration profiles
     * ============================================================
     */

    /**
     * Strict, production-grade security.
     * No insecure connections, proper TLS, strict hostname checks.
     */
    static SecurityConfig production(String baseUrl = "https://example.com") {
        new SecurityConfig([
                baseUrl                    : baseUrl,
                insecureAllowed             : false,
                allowAbsoluteUrls           : false,
                cookiePolicy                : CookiePolicy.ACCEPT_ORIGINAL_SERVER,
                enforceHostnameVerification : true,
                connectTimeout              : Duration.ofSeconds(5),
                requestTimeout              : Duration.ofSeconds(20),
                maxResponseBytes            : 10_000_000,
                allowedTlsProtocols         : ["TLSv1.3"],
                enableLogging               : false,
                enableMetrics               : true
        ])
    }

    /**
     * Staging environment:
     * Same as production but with logging enabled.
     */
    static SecurityConfig staging(String baseUrl = "https://staging.example.com") {
        new SecurityConfig([
                baseUrl                    : baseUrl,
                insecureAllowed             : false,
                allowAbsoluteUrls           : false,
                cookiePolicy                : CookiePolicy.ACCEPT_ORIGINAL_SERVER,
                enforceHostnameVerification : true,
                connectTimeout              : Duration.ofSeconds(5),
                requestTimeout              : Duration.ofSeconds(30),
                enableLogging               : true
        ])
    }

    /**
     * Testing profile. Allows insecure connections and absolute URLs.
     */
    static SecurityConfig testing(String baseUrl = "http://localhost:8080") {
        new SecurityConfig([
                baseUrl          : baseUrl,
                insecureAllowed   : true,
                allowAbsoluteUrls : true,
                cookiePolicy      : CookiePolicy.ACCEPT_ALL,
                enableLogging     : true,
                enableMetrics     : false
        ])
    }

    /**
     * Local development or unit test environment.
     * Completely disables all TLS verification.
     *
     * WARNING: For testing only. Do not use in production environments.
     */
    static SecurityConfig trustAll(String baseUrl = "http://localhost:8080") {
        new SecurityConfig([
                baseUrl                    : baseUrl,
                insecureAllowed             : true,   // Allow self-signed or invalid certs
                allowAbsoluteUrls           : true,
                cookiePolicy                : CookiePolicy.ACCEPT_ALL,
                enforceHostnameVerification : false,
                allowedTlsProtocols         : ["TLSv1.3", "TLSv1.2"],
                allowedCipherSuites         : [],

                connectTimeout              : Duration.ofSeconds(3),
                requestTimeout              : Duration.ofSeconds(30),
                failureThreshold            : 1,
                resetTimeoutMs              : 1000,

                enableLogging               : true,
                enableMetrics               : false
        ])
    }

    /**
     * Modify an existing config with overrides.
     */
    SecurityConfig withOverrides(Map overrides) {
        new SecurityConfig(this.properties + overrides)
    }

    @Override
    String toString() {
        """SecurityConfig(
  baseUrl=${baseUrl},
  insecureAllowed=${insecureAllowed},
  allowAbsoluteUrls=${allowAbsoluteUrls},
  cookiePolicy=${cookiePolicy},
  connectTimeout=${connectTimeout},
  requestTimeout=${requestTimeout},
  allowedTlsProtocols=${allowedTlsProtocols},
  enforceHostnameVerification=${enforceHostnameVerification},
  maxResponseBytes=${maxResponseBytes},
  enableLogging=${enableLogging},
  enableMetrics=${enableMetrics}
)"""
    }
}