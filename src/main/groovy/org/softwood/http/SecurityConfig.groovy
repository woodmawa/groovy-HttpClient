package org.softwood.http

import groovy.util.logging.Slf4j

import java.net.CookiePolicy
import java.time.Duration

/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * SecurityConfig.groovy
 * ------------------------------------------------------------
 * Centralized configuration class for defining and enforcing
 * security and environment-specific policies for the GroovyHttpClient.
 *
 * <p>Encapsulates connection security, timeouts, TLS options, and
 * cookie handling behavior across multiple deployment profiles.</p>
 *
 * <p>Features include:</p>
 * <ul>
 *   <li>Predefined profiles for production, staging, and testing</li>
 *   <li>Enforcement of TLS versions and hostname verification</li>
 *   <li>Customizable timeouts and circuit breaker thresholds</li>
 *   <li>Cookie policy and absolute URL restrictions</li>
 *   <li>Support for fine-grained runtime overrides</li>
 * </ul>
 *
 * <p>Used internally by {@link org.softwood.http.GroovyHttpClient}
 * to standardize HTTP client configuration and ensure secure defaults.</p>
 *
 * @author  Will Woodman
 * @version 1.0-RELEASE
 * @since   2025-11
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