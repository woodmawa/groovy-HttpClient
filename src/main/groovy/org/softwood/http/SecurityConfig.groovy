package org.softwood.http

import java.net.CookiePolicy
import java.time.Duration

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
 */
class SecurityConfig {

    /** --- Core connection parameters --- **/
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

    /** --- Optional retry & circuit breaker --- **/
    final int failureThreshold
    final long resetTimeoutMs

    /** --- Optional TLS configuration fields --- **/
    final boolean enforceHostnameVerification
    final List<String> allowedTlsProtocols
    final List<String> allowedCipherSuites

    private SecurityConfig(Map args) {
        this.baseUrl = args.baseUrl
        this.connectTimeout = args.connectTimeout ?: Duration.ofSeconds(10)
        this.requestTimeout = args.requestTimeout ?: Duration.ofSeconds(30)
        this.insecureAllowed = args.insecureAllowed ?: false
        this.allowAbsoluteUrls = args.allowAbsoluteUrls ?: false
        this.maxResponseBytes = args.maxResponseBytes ?: 10_000_000L
        this.cookiePolicy = args.cookiePolicy ?: CookiePolicy.ACCEPT_ORIGINAL_SERVER
        this.allowedHosts = args.allowedHosts ?: [URI.create(baseUrl).host] as Set
        this.enableLogging = args.enableLogging ?: false
        this.enableMetrics = args.enableMetrics ?: true
        this.allowRedirects = args.allowRedirects ?: true
        this.failureThreshold = args.failureThreshold ?: 5
        this.resetTimeoutMs = args.resetTimeoutMs ?: 30_000L
        this.enforceHostnameVerification = args.enforceHostnameVerification ?: true
        this.allowedTlsProtocols = args.allowedTlsProtocols ?: ["TLSv1.3"]
        this.allowedCipherSuites = args.allowedCipherSuites ?: []
    }

    /** --- Predefined safe profiles --- **/

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

    /** --- Builder-style clone for quick tweaks --- **/
    SecurityConfig withOverrides(Map overrides) {
        new SecurityConfig(this.properties + overrides)
    }

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
