# groovy-HttpClient — security and design review

**Baseline:** v1.0.2 (`f00178d`, 2026-01-26) · Groovy 5.0.3 · Gradle 9.2.0 · JDK 25 toolchain, Java 21 bytecode
**Reviewed:** 2026-10-24 · all of `src/main` read end to end (GroovyHttpClient, SecurityConfig, MultipartPart, URLBuilder), plus build and docs
**Evidence:** `src/test/groovy/org/softwood/http/security/SecurityReviewSpec.groovy`

Every SEC-n finding below has a feature in `SecurityReviewSpec` stating the behaviour the library *should* have. Each was run with `@PendingFeature` disabled and confirmed to fail for the stated reason (14 failing features, 0 failing for an unrelated reason). They are committed with `@PendingFeature`, so the suite stays green while a defect is open and the feature turns red as soon as a fix makes it pass — removing the annotation is part of each fix. The four SEC-G guards pass today and must stay green through the Groovy 6 / Java uplift and the HTTP/3 work.

Baseline suite: 75 tests green. With the review spec: 94 tests green.

## Summary

| ID | Severity | Finding | Spec |
|---|---|---|---|
| SEC-1 | **Critical** | `new GroovyHttpClient(String)` uses `SecurityConfig.testing()` → trust-all TLS. Every README and asciidoc quick-start uses this constructor. | red |
| SEC-2 | High | `allowRedirects(false)` / `enableMetrics(false)` are silently ignored (`x ?: true` coerces `false` to `true`), in the Builder and in `withOverrides`. | red ×4 |
| SEC-3 | High | Redirects bypass the `allowedHosts` SSRF guard — the JDK client follows them, the guard only checks the first URI. | red + control |
| SEC-4 | Medium | Status-code retry (`on5xx`, `onStatusCodes`) is dead code: the terminal stage throws on ≥400 before retry sees the response. The production profile's 5xx retry never fires. | red |
| SEC-5 | High | `maxResponseBytes` is checked only after `BodyHandlers.ofByteArray` has buffered the whole body. An endless or huge response exhausts memory or hangs. | red |
| SEC-6 | Medium | Multipart `name`, `filename` and `contentType` are interpolated raw into part headers → header/part injection via CR/LF or `"`. | red ×3 |
| SEC-7 | Medium | `cookie(name, value)` / `cookies(map)` concatenate raw → `;` smuggles extra cookies. | red |
| SEC-8 | Medium | Circuit breaker HALF_OPEN admits every caller, not one probe (the code comment claims "single probe in-flight"). | red |
| SEC-9 | Low | `close()` shuts the executor but never closes the `java.net.http.HttpClient` (selector thread and connections leak). | red |
| SEC-10 | Medium | Retries apply to non-idempotent methods. `production()` retries POST/PATCH on IOException and timeout → duplicate writes. | not yet specced |
| SEC-11 | Low | `HttpResponseException` puts the full response body in its message, so error bodies (tokens, PII) end up in logs. | not yet specced |
| SEC-12 | Medium | Default headers and `cookieHandler` cookies (e.g. `Authorization`) go to every allowed absolute-URL host, and to redirect targets while the JDK follows redirects. Closed by the SEC-3 fix if redirects are handled in-library. | covered by SEC-3 fix |

Guards (green today): **G1** XML with a DOCTYPE/external entity is refused (XXE) · **G2** an absolute URL to a host outside `allowedHosts` (e.g. 169.254.169.254) is refused · **G3** absolute URLs are refused by default · **G4** `production()` verifies certificates and host names, TLS 1.3 only.

## Findings in detail

### SEC-1 — Critical — trust-all TLS by default
`GroovyHttpClient(String baseUrl) { this(SecurityConfig.testing(baseUrl)) }`. `testing()` sets `insecureAllowed: true`, which installs an `X509TrustManager` that accepts any certificate. Hostname verification stays on, but with a trust-all manager that is no protection: an attacker presents a certificate for the right name, signed by anyone. A WARN is logged, but the README, chapter 1, chapter 5 and chapter 11 all open with `new GroovyHttpClient("https://api.example.com")`, so the documented happy path is insecure.

**Fix:** the String constructor uses `production(baseUrl)` semantics. `testing()` should verify certificates against a supplied test trust store (the repo already has `src/test/resources/keystore.jks`); trust-all exists only as `SecurityConfig.trustAll()`, named for what it does. This is a behaviour change → major version.

### SEC-2 — High — `false` can't be set for true-by-default booleans
`SecurityConfig(Map)` uses `(args.allowRedirects ?: true) as boolean` and the same for `enableMetrics`. Groovy truth makes an explicit `false` fall through to `true`. `enforceHostnameVerification` uses `!= null` and is correct, which is the pattern to copy. Ints and longs share the flaw (`failureThreshold: 0`, `maxResponseBytes: 0` become defaults) but those are less dangerous.

**Fix:** `args.containsKey('x') ? args.x as boolean : default` for every field, plus a spec per field.

### SEC-3 — High — redirects escape the SSRF guard
`followRedirects(NORMAL)` hands redirects to the JDK. `resolveUri` checks `allowedHosts` for the initial request only, so a 302 to an internal address is followed. The control feature proves the red case fails for the policy reason: the same redirect is followed when the host is allowed.

**Fix:** always build the JDK client with `Redirect.NEVER` and follow redirects in the library: re-check each `Location` against `allowedHosts`, refuse HTTPS→HTTP, cap the number of hops, and drop `Authorization`/`Cookie` when the origin changes. That also closes SEC-12 and makes `allowRedirects` real.

### SEC-4 — Medium — status-code retry never runs
`buildInterceptorChain`'s terminal stage throws `HttpResponseException` for `statusCode >= 400`. `attemptWithRetry` only gets a `resp` for status-based retry on success, and `shouldRetry(HttpResponseException, null)` returns false because it is not an IOException. There is also a dead first `doAttempt` closure.

**Fix:** move the ≥400 throw out of the terminal stage into a final `thenApply` after retry, and delete the unused closure. The circuit breaker's "count only 5xx" logic still works on the final outcome.

### SEC-5 — High — response cap after full buffering
`BodyHandlers.ofByteArray()` reads the whole body before the `> maxResponseBytes` check runs. An endless or very large response hangs (request timeout covers headers, not the body) or exhausts memory.

**Fix:** a bounded `BodyHandler`: reject early when `Content-Length` is over the cap, otherwise count bytes in a `BodySubscriber` and cancel the subscription once the cap is crossed. The `downloadBytes` path needs the same handler.

### SEC-6 — Medium — multipart header injection
`buildMultipartBody` writes `name="${part.name}"; filename="${part.filename}"` and `Content-Type: ${part.contentType}` verbatim. A filename such as `a.txt"\r\nContent-Type: text/html\r\n\r\n<script>` injects headers or whole parts.

**Fix:** validate in the `MultipartPart` constructor (every path goes through it). Reject CR, LF and NUL. Percent-encode `"` per RFC 7578 §4.2, and add `filename*=UTF-8''…` for non-ASCII names. `contentType` must parse as a media type.

### SEC-7 — Medium — cookie smuggling
`cookie(name, value)` builds `"${name}=${value}"`. A value containing `;` adds cookies the caller never asked for. `SimpleCookieHandler.asHeaderValue()` has the same flaw.

**Fix:** validate the name as an RFC 6265 token and the value against cookie-octet (or quoted). Reject anything else.

### SEC-8 — Medium — HALF_OPEN is a stampede, not a probe
`tryAcquirePermission()` returns `true` for every caller once the state is HALF_OPEN, so a recovering backend takes the full load at once. State changes are also plain volatile writes across several fields, which can race.

**Fix:** an `AtomicReference<State>` and a CAS-guarded "probe in flight" flag. Only the probe winner proceeds, and every other caller gets `CircuitOpenException` until the probe resolves.

### SEC-9 — Low — `close()` leaks the HttpClient
`close()` closes only the executor. Since JDK 21 `HttpClient` is `AutoCloseable` and should be closed: `httpClient.close()` (or `shutdownNow()` with a bounded `awaitTermination`), then the executor.

### SEC-10 — Medium — retrying non-idempotent requests
Retry applies to every method. `production()` turns on `onIoExceptions` and `onTimeouts`, so a POST whose response was lost after the server committed it gets sent again.

**Fix:** retry only idempotent methods (GET, HEAD, OPTIONS, PUT, DELETE) by default, and add `retryNonIdempotent(true)` or an `Idempotency-Key` hook as an explicit opt-in.

### SEC-11 — Low — response bodies in exception messages
`HttpResponseException("HTTP error: $statusCode, body: $responseBody")`.

**Fix:** keep the body on the exception as a field. Put only the status and a short truncated, sanitised snippet in the message.

## Correctness and design findings (not security)

- **C-1 Per-request DSL settings are silently dropped.** The DSL configures `rb`, but `requestSupplier` rebuilds the request from `finalUri` + config timeout + copied headers. So `timeout(...)`, and anything forwarded through `GroovyRequestBuilder.methodMissing` (e.g. `version`, `expectContinue`, `setHeader`), never reaches the wire. This matters for the HTTP/3 work, because a per-request `version(HTTP_3)` or `H3_DISCOVERY` option would be lost the same way. Fix this before HTTP/3: record per-request settings in the wrapper and replay them in the supplier, and remove `methodMissing`.
- **D-1 `logback-classic` is an `implementation` dependency.** A library should depend on `slf4j-api` only. Shipping logback forces a backend on consumers and adds it to their CVE surface. Move it to `testRuntimeOnly`.
- **D-2 `org.softwood.testScripts` ships in the published jar** (TestHttpClient, TestURLBuilder, a scratch script). Move them to `src/test` or delete them.
- **D-3 `GroovyHttpClient.groovy` is 1,400 lines** with nine nested types and no `@CompileStatic`. Splitting out `RetryPolicy`, `CircuitBreaker`, `HttpClientResponse` and the exceptions into top-level `@CompileStatic` classes would make the security-sensitive paths type-checked and easier to test. The public API stays the same if the nested names are kept as aliases for one major version.
- **D-4 Docs drift.** The Javadoc says "Built on top of Java 25" while the bytecode is 21. `SecurityConfig`'s doc says `@since 1.4.0` and refers to a `GroovyHttpClientBuilder` that doesn't exist (it's `GroovyHttpClient.builder()`).
- **D-5 `URLBuilder.isValidURL`** declares checked exceptions it swallows, and `URLBuilder` doesn't encode `path`. That's fine for a builder, but worth noting before anyone feeds it user input.
- **D-6 `gradle.properties` is tracked despite `.gitignore` listing it under "Build Secrets".** It was committed before the ignore rule, so the rule has never applied. Its full history (4 commits) and the whole repo history were checked on 2026-10-24: no credentials were ever committed. Versions moved to `gradle/libs.versions.toml` in the uplift, and the file now carries a warning not to put secrets in it. Either drop the ignore entry or `git rm --cached` the file, so the label stops being misleading.
- **D-7 The published javadoc jar is empty.** `javadoc` is NO-SOURCE for a Groovy-only project, so `withJavadocJar()` packages nothing. Package the `groovydoc` output as the `-javadoc` classifier before the next Central publish.
- **D-8 Supply chain.** The wrapper now pins `distributionSha256Sum` (taken from services.gradle.org). `settings.gradle` still lists `mavenLocal()`, which can shadow Central artifacts on a build machine. Drop it for release builds.

## Recommended order

1. Uplift the toolchain (Gradle, Groovy 6, Spock, Java) with the suite green and the pending specs still pending. That proves the uplift changed no behaviour.
2. Fix SEC-1, 2, 3 (+12), 5 — the security-critical set — removing each `@PendingFeature` as it goes green.
3. Fix SEC-4, 6, 7, 8, 9, 10, 11, C-1, D-1, D-2.
4. HTTP/3 (needs C-1 first).
5. Refresh the asciidoc book: quick-start examples, the security chapter, redirect and retry semantics, HTTP/3. Then republish as **2.0.0**, since SEC-1, SEC-4 and SEC-10 change observable behaviour.
