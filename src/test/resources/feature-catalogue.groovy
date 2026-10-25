// groovy-HttpClient feature catalogue -- the single list of every public capability.
//
//   id           area.name, lower case; specs reference it with @Feature('area.name')
//   status       implemented | planned   (flip to implemented in the commit that ships the feature)
//   version      release that shipped it, or the target release while planned
//   chapter      docs/chapters file stem that documents it
//   integration  true = an integrationTest spec against the real Jetty fixture is required
//   protocols    protocols that spec must prove (ALL = HTTP_1_1, HTTP_2, HTTP_3); HTTP_3 runs on JDK 26+ only
//   minJdk       lowest runtime JDK the feature exists on; the gate does not ask for it on older JDKs
//
// Gates (FeatureCoverageSpec): every implemented entry has a tagged spec; integration entries have a tagged
// integrationTest spec; every @Feature id exists here. Planned entries are reported, never failed.
[
    // ---- requests -------------------------------------------------------------------------------------------
    [id: 'request.verbs',        title: 'GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS (async)', status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.sync',         title: 'Sync variants (getSync ...) throw the real failure, not ExecutionException', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.headers',      title: 'Default client headers and per-request headers',             status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.query',        title: 'Query parameters from a map, encoded',                       status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.json-body',    title: 'json(object) request body with Content-Type',                status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.form-body',    title: 'form(map) application/x-www-form-urlencoded body',           status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.multipart',    title: 'multipart/form-data upload (DSL and part list)',             status: 'implemented', version: '1.0', chapter: '06-multipart-uploads',  integration: true,  protocols: ALL],
    [id: 'request.cookies',      title: 'Client cookie store and per-request cookies (RFC 6265 validated)', status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true, protocols: ALL],
    [id: 'request.timeout',      title: 'Connect / request timeouts, per-request override',           status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.interceptors', title: 'Interceptor chain around every request',                     status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'request.dsl-validation', title: 'Unknown request-DSL calls fail loudly',                    status: 'implemented', version: '2.0', chapter: '12-upgrading-2.0',      integration: false],

    // ---- responses ------------------------------------------------------------------------------------------
    [id: 'response.headers',     title: 'Response headers: single, multi-value, case-insensitive',    status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'response.json',        title: 'Parse JSON responses (json / jsonOrNull / requireJson)',     status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'response.xml',         title: 'Parse XML responses, DOCTYPE / external entities refused',   status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'response.raise-for-status', title: '4xx/5xx raise HttpResponseException with responseBody', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'response.max-bytes',   title: 'maxResponseBytes enforced while streaming',                  status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: true,  protocols: ALL],
    [id: 'response.download-bytes', title: 'downloadBytes / downloadBytesSync (in memory)',           status: 'implemented', version: '1.0', chapter: '06-multipart-uploads',  integration: true,  protocols: ALL],

    // ---- redirects ------------------------------------------------------------------------------------------
    [id: 'redirect.follow',      title: 'Library-managed redirects, maxRedirects, 301/302/303 POST rewrite', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: true, protocols: ALL],
    [id: 'redirect.allowed-hosts', title: 'Every redirect hop checked against allowedHosts',          status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: false],
    [id: 'redirect.credential-strip', title: 'Authorization / Cookie stripped on cross-origin redirects', status: 'implemented', version: '2.0', chapter: '03-security-config', integration: false],

    // ---- security -------------------------------------------------------------------------------------------
    [id: 'security.profiles',    title: 'defaults / production / testing / trustAll profiles; explicit false honoured', status: 'implemented', version: '2.0', chapter: '03-security-config', integration: false],
    [id: 'security.allowed-hosts', title: 'allowedHosts and absolute-URL refusal',                    status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: false],
    [id: 'security.tls-trust',   title: 'Certificate verification, custom trust store / SSLContext', status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: false],
    [id: 'security.mtls',        title: 'Mutual TLS via keyManagers',                                 status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: false],
    [id: 'security.unknown-keys', title: 'Unknown SecurityConfig keys are refused',                   status: 'implemented', version: '2.0', chapter: '03-security-config',    integration: false],

    // ---- resilience -----------------------------------------------------------------------------------------
    [id: 'resilience.retry',     title: 'RetryPolicy: backoff, 5xx / IO retry, POST/PATCH only with retryNonIdempotent', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false],
    [id: 'resilience.circuit-breaker', title: 'Circuit breaker CLOSED / OPEN / HALF_OPEN (single probe)', status: 'implemented', version: '1.0', chapter: '07-circuit-breaker', integration: false],

    // ---- protocols ------------------------------------------------------------------------------------------
    [id: 'protocol.negotiated',  title: 'The protocol asked for is the protocol used (h1 / h2 / h3)', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: true,  protocols: ALL],
    [id: 'protocol.version-config', title: 'httpVersion / http3Discovery configuration and validation', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false],
    [id: 'protocol.http2',       title: 'HTTP/2 multiplexing over one connection',                    status: 'implemented', version: '1.0', chapter: '05-groovy-http-client', integration: false],
    [id: 'protocol.http3',       title: 'HTTP/3 (QUIC) on JDK 26+',                                    status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false, minJdk: 26],
    [id: 'protocol.http3-fallback', title: 'HTTP/3 falls back to HTTP/2 on JDK 25; HTTP_3_URI_ONLY never falls back', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false],

    // ---- client, observability, URL builder, testing support ------------------------------------------------
    [id: 'client.lifecycle',     title: 'close() releases the underlying HttpClient',                 status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false],
    [id: 'observability.logging', title: 'enableLogging: status line logged without query string or credentials', status: 'implemented', version: '2.0', chapter: '05-groovy-http-client', integration: false],
    [id: 'url.builder',          title: 'URLBuilder fluent and closure DSL',                          status: 'implemented', version: '1.0', chapter: '04-url-builder',        integration: false],
    [id: 'testing.mock-server',  title: 'MockHttpServer',                                             status: 'implemented', version: '1.0', chapter: '08-testing-mock-server', integration: false],
    [id: 'testing.dsl',          title: 'HttpClientTestDsl',                                          status: 'implemented', version: '1.0', chapter: '09-testing-dsl',        integration: false],
    [id: 'testing.trait',        title: 'HttpClientTestTrait',                                        status: 'implemented', version: '1.0', chapter: '10-testing-trait',      integration: false],

    // ==== 2.1 (ideation #177) =================================================================================
    [id: 'codec.registry',       title: 'encoder(contentType) / parser(contentType) registry',        status: 'implemented', version: '2.1', integration: true,  protocols: ALL],
    [id: 'codec.csv',            title: 'Built-in CSV encoder and parser',                            status: 'implemented', version: '2.1', integration: false],
    [id: 'codec.structured-suffix', title: '+json / +xml structured-suffix matching (problem+json, vnd.tmf+json)', status: 'implemented', version: '2.1', integration: true, protocols: ALL],
    [id: 'codec.typed-result',   title: 'Typed results: get(Customer)',                               status: 'implemented', version: '2.1', integration: false],
    [id: 'codec.jackson',        title: 'Optional Jackson codec via ServiceLoader',                   status: 'implemented', version: '2.1', integration: false],
    [id: 'stream.download-path', title: 'Download to Path / OutputStream, maxResponseBytes enforced', status: 'planned', version: '2.1', integration: true,  protocols: ALL],
    [id: 'stream.upload',        title: 'Streaming upload from Path / InputStream / Publisher',       status: 'planned', version: '2.1', integration: true,  protocols: ALL],
    [id: 'stream.lines',         title: 'Line / SSE / NDJSON stream reader',                          status: 'planned', version: '2.1', integration: true,  protocols: ALL],
    [id: 'response.handlers',    title: 'response { success {}; failure {}; when(404) {}; exception {} }', status: 'planned', version: '2.1', integration: true, protocols: ALL],
    [id: 'response.error-detail', title: 'HttpResponseException: no body in message by default; headers, method, uri, contentType, bodyAs; errorBodyPreviewChars', status: 'planned', version: '2.1', integration: true, protocols: ALL],
    [id: 'response.problem-details', title: 'RFC 9457 application/problem+json parsed as e.problem', status: 'planned', version: '2.1', integration: true, protocols: ALL],
    [id: 'response.size-limit-exception', title: 'ResponseTooLargeException (limit, bytesRead) for the client-side maxResponseBytes limit', status: 'planned', version: '2.1', integration: true, protocols: ALL],
    [id: 'compression.gzip-response', title: 'Opt-in gzip / deflate response decompression',          status: 'planned', version: '2.1', integration: true,  protocols: ALL],
    [id: 'compression.zip-bomb-guard', title: 'maxResponseBytes counts decompressed bytes',           status: 'planned', version: '2.1', integration: true,  protocols: ALL],
    [id: 'compression.gzip-request', title: 'Optional gzip request bodies',                           status: 'planned', version: '2.1', integration: true,  protocols: ALL],

    // ==== 2.2 (ideation #178) =================================================================================
    [id: 'auth.basic',           title: 'Basic auth interceptor',                                     status: 'planned', version: '2.2', integration: true,  protocols: ALL],
    [id: 'auth.bearer',          title: 'Bearer token interceptor',                                   status: 'planned', version: '2.2', integration: true,  protocols: ALL],
    [id: 'auth.api-key',         title: 'API key (header or query) interceptor',                      status: 'planned', version: '2.2', integration: false],
    [id: 'auth.oauth2-client-credentials', title: 'OAuth2 client credentials: cached token, single-flight refresh on 401', status: 'planned', version: '2.2', integration: true, protocols: ALL],
    [id: 'proxy.http',           title: 'HTTP proxy (CONNECT) configuration',                         status: 'planned', version: '2.2', integration: true,  protocols: ['HTTP_1_1', 'HTTP_2']],
    [id: 'proxy.socks',          title: 'SOCKS proxy configuration',                                  status: 'planned', version: '2.2', integration: false],
    [id: 'proxy.auth',           title: 'Proxy authentication; allowedHosts still checked on the target', status: 'planned', version: '2.2', integration: true, protocols: ['HTTP_1_1', 'HTTP_2']],
    [id: 'testing.published-fixtures', title: 'Mock server / DSL / trait published as test fixtures', status: 'planned', version: '2.2', integration: false],
]
