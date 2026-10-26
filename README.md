# Groovy HTTP Client

A production-ready HTTP client for Groovy and Java, built on the JDK `HttpClient` and virtual threads: secure defaults, HTTP/1.1, HTTP/2 and HTTP/3, a Groovy request DSL, and a testing toolkit you can use in your own tests.

[![Java](https://img.shields.io/badge/Java-25+-blue.svg)](https://openjdk.org/projects/jdk/25/)
[![Groovy](https://img.shields.io/badge/Groovy-6.0+-orange.svg)](https://groovy-lang.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.woodmawa/groovy-httpclient.svg)](https://central.sonatype.com/artifact/io.github.woodmawa/groovy-httpclient)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## What's new in 2.1

2.1 is source-compatible with 2.0 and every new capability is opt-in.

- **Codecs and typed results**: JSON, XML, CSV, form and text built in, optional Jackson, `+json` media types such as `application/problem+json`, and `getSync(Customer, '/customers/42')`.
- **Response handlers**: `response { success {}; when(404) {}; failure {}; exception {} }` turns outcomes into values.
- **Error detail**: `HttpResponseException` carries status, headers, method, URI and body as fields, parses RFC 9457 problem details, and keeps the body out of its message.
- **Streaming**: downloads to a file or stream; uploads from a file, stream or publisher; line, Server-Sent Event and NDJSON consumers.
- **Compression**: opt-in gzip/deflate, with `maxResponseBytes` counting decompressed bytes.
- **Authentication**: basic, bearer, API key and OAuth2 client credentials.
- **Proxies**: an explicit HTTP proxy with authentication and bypass rules; SOCKS is refused rather than silently bypassed.
- **Published test fixtures**: `MockHttpServer`, `HttpClientTestDsl` and `HttpClientTestTrait` as a `test-fixtures` artifact.
- **Observability**: per-client metrics (`client.metrics.snapshot()`), circuit breaker inspection (`client.circuitBreakerStatus`) and state-change listeners.

Behaviour changes to check when upgrading from 2.0 are listed under [Upgrading](#upgrading).

---

## Installation

**Gradle**

```groovy
dependencies {
    implementation 'io.github.woodmawa:groovy-httpclient:2.1.0'

    // optional: the mock server, test DSL and Spock trait for your own tests
    testImplementation testFixtures('io.github.woodmawa:groovy-httpclient:2.1.0')
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.woodmawa</groupId>
    <artifactId>groovy-httpclient</artifactId>
    <version>2.1.0</version>
</dependency>

<!-- optional test fixtures -->
<dependency>
    <groupId>io.github.woodmawa</groupId>
    <artifactId>groovy-httpclient</artifactId>
    <version>2.1.0</version>
    <classifier>test-fixtures</classifier>
    <scope>test</scope>
</dependency>
```

**Requirements**: Java 25+ (HTTP/3 needs JDK 26+), Groovy 6.0+, and any SLF4J 2.x binding. `jackson-databind` 2.x is optional.

---

## Quick start

```groovy
import org.softwood.http.GroovyHttpClient

def client = new GroovyHttpClient('https://api.example.com')

def response = client.getSync('/users/123')
println response.statusCode
println response.json.name
```

`new GroovyHttpClient(url)` uses `SecurityConfig.defaults(url)`: certificates and host names are verified, absolute URLs are refused, redirects are followed only to allowed hosts, and nothing is retried. For anything else use the builder:

```groovy
def client = GroovyHttpClient.builder('https://api.example.com')
        .requestTimeout(Duration.ofSeconds(10))
        .retryPolicy(new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(3); backoffExponential(200, 2_000, true)
        }.build())
        .compression(true)
        .auth { bearer token }
        .build()
```

Every verb has a synchronous form (`getSync`, returning the result) and an asynchronous one (`get`, returning a `CompletableFuture`).

---

## Requests

```groovy
client.getSync('/search') {
    query(q: 'groovy', limit: 10)
    header 'Accept', 'application/json'
    cookie 'session', sessionId
    timeout Duration.ofSeconds(30)
}

client.postSync('/users', null) { json([name: 'Alice', email: 'alice@example.com']) }
client.postSync('/login', null) { form(username: 'alice', password: secret) }
client.putSync('/users/123', null) { body(user, 'application/json') }
client.deleteSync('/users/123')
```

Requests and responses are encoded and parsed by a **codec registry**:

```groovy
client.postSync('/report', null) { body(rows, 'text/csv') }      // List<Map> -> CSV with a header row

def r = client.getSync('/orders/42')
r.parsed                     // Map/List for JSON, GPathResult for XML, List<Map> for CSV, String for text
Order o = r.bodyAs(Order)    // or: r as Order

Order typed = client.getSync(Order, '/orders/42')                 // typed verbs
CompletableFuture<Order> later = client.get(Order, '/orders/42')
```

Add your own codecs, or use Jackson (picked up automatically when `jackson-databind` 2.x is on the classpath):

```groovy
def client = GroovyHttpClient.builder(url).codecs {
    parser 'application/vnd.tmf+json', { String text, MediaType type, Class target -> tmf.read(text, target) }
    encoder 'text/tab-separated-values', { rows -> rows*.join('\t').join('\n') }
    // jackson(myObjectMapper)
}.build()
```

---

## Responses and errors

A status of 400 or above is raised as `HttpResponseException` after retries. Its message is safe to log: status, reason, method, the URI without its query string, size and content type, and no body unless you opt in with `errorBodyPreviewChars`.

```groovy
try {
    client.postSync('/orders', null) { body(order, 'application/json') }
} catch (GroovyHttpClient.HttpResponseException e) {
    log.warn(e.message)       // HTTP 422 Unprocessable Content (POST https://api.example.com/orders, 87 bytes, application/problem+json)
    e.statusCode
    e.responseBody
    e.header('X-Trace-Id')
    e.bodyAs(ValidationErrors)
    e.problem?.detail         // RFC 9457 application/problem+json
}
```

**Response handlers** turn outcomes into values:

```groovy
Order order = client.getSync(Order, '/orders/42') {
    response {
        success   { r -> r.bodyAs(Order) }
        when(404) { r -> null }
        when(500..599) { r -> cachedOrder() }
        exception { Throwable t -> cachedOrder() }
    }
}
```

A body over `maxResponseBytes` raises `ResponseTooLargeException` (a subclass of `HttpResponseException`, status 413).

---

## Streaming

```groovy
// Download to a file: written to a temp file and moved into place only when complete
StreamResult r = client.downloadSync('/exports/2026-10.csv', Path.of('2026-10.csv'))
client.downloadSync('/report.pdf', outputStream)                     // or into your stream

// Small files can still be read into memory
byte[] bytes = client.downloadBytesSync('/files/logo.png')

// Lines, NDJSON and Server-Sent Events, delivered as they arrive
client.linesSync('/logs/tail') { String line -> println line }
client.ndjsonSync('/feed') { Map record -> store(record) }
client.sseSync('/events') { SseEvent e, StreamControl c ->
    if (e.event == 'done') c.stop() else handle(e.data)
}

// Any verb can stream: e.g. an LLM-style POST that answers with SSE
client.postSync(StreamResult, '/v1/chat', JsonOutput.toJson(prompt)) {
    header 'Content-Type', 'application/json'
    sse { SseEvent e -> print e.data }
}

// Uploads from a file (replayable), a stream or a publisher (one-shot)
client.putSync('/files/42', null) { body Path.of('big.bin') }
client.postSync('/ingest', null) { body inputStream, 'application/x-ndjson' }
```

Each line or event is bounded by `maxStreamItemBytes` (1 MiB by default); for long-lived streams, raise `maxResponseBytes` per request and set an `idleTimeout`. A one-shot upload is never retried, and a 307/308 redirect that would need it re-sent is refused.

---

## Compression

```groovy
def client = GroovyHttpClient.builder(url).compression(true).build()   // Accept-Encoding: gzip, deflate

client.postSync('/ingest', null) { body(records, 'application/json'); gzipBody() }   // compress a request body
```

The size limit counts decompressed bytes, so a small compressed body cannot inflate past it. Decoded responses report `r.decoded` and `r.contentEncoding`.

---

## Authentication

```groovy
builder(url).auth { basic 'svc-user', secret }
builder(url).auth { bearer { -> vault.read('token') } }          // supplier, asked on every attempt
builder(url).auth { apiKey header: 'X-API-Key', value: key }
builder(url).auth {
    oauth2 {
        tokenUrl 'https://login.example.com/oauth2/token'
        clientId 'orders-service'
        clientSecret secret
        scope 'orders.read'
    }
}

client.getSync('/admin') { auth { bearer adminToken } }   // per request
client.getSync('/health') { noAuth() }
```

Credentials go only to the base URL's origin (add others with `authHosts`) and are removed on cross-origin redirects. OAuth2 tokens are cached, fetched once for concurrent callers, and refreshed once on a 401.

---

## Proxies

```groovy
GroovyHttpClient.builder(url)
        .proxy('proxy.corp.example', 3128)
        .proxyAuth('svc-user', proxyPassword)
        .nonProxyHosts('*.internal', 'localhost')
        .build()
```

Without these the JVM's proxy settings apply, as before; `noProxy()` connects directly.

- **HTTPS targets behind a Basic-auth proxy**: the JDK removes Basic credentials from `CONNECT` by default. Start the JVM with `-Djdk.http.auth.tunneling.disabledSchemes=""`. Without it the request fails with an `AuthenticationException` that says so.
- **SOCKS is not supported**: the JDK `HttpClient` ignores SOCKS proxies and connects directly, so a SOCKS proxy is refused at build time, and a warning is logged if the JVM's own settings select one.

---

## Security configuration

```groovy
new GroovyHttpClient(SecurityConfig.production('https://api.example.com'))
new GroovyHttpClient(SecurityConfig.staging('https://staging.example.com'))
new GroovyHttpClient(SecurityConfig.testing('https://localhost:8443'))   // verifies TLS; short timeouts, retries, logging

def config = SecurityConfig.production('https://api.example.com').withOverrides(
        maxResponseBytes: 5_000_000L,
        allowedHosts: ['api.example.com', 'cdn.example.com'] as Set,
        trustStore: corporateCaStore)
```

Unknown settings are refused, so a misspelt security setting fails at once. `SecurityConfig.trustAll(url)` accepts any certificate and is for local debugging only. For certificates, trust stores and mutual TLS see the [SSL certificate guide](SSL_CERTIFICATE_GUIDE.md).

---

## Resilience

```groovy
def client = GroovyHttpClient.builder(url)
        .failureThreshold(5)            // circuit breaker opens after 5 failures
        .resetTimeoutMs(30_000)         // then lets a single probe through after 30 s
        .build()

client.getSync('/orders') {
    retry { maxAttempts 3; backoffExponential(200, 2_000, true); on5xx(true) }
}

client.addInterceptor { ctx, next ->
    long t = System.nanoTime()
    next.call(ctx).thenApply { resp -> metrics.record(ctx.method, resp.statusCode, System.nanoTime() - t); resp }
}
```

POST and PATCH are retried only with `retryNonIdempotent(true)`. While the breaker is open, requests fail at once with `CircuitOpenException`.

---

## HTTP/3

```groovy
def client = GroovyHttpClient.builder('https://api.example.com').http3().build()   // JDK 26+; HTTP/2 otherwise
client.getSync('/data').httpVersion   // HTTP_3 when QUIC was negotiated
```

---

## Testing your code

The test fixtures give you a real HTTP server for your tests:

```groovy
import org.softwood.test.MockHttpServer

def server = new MockHttpServer()
server.init()
def check = server.addRequestCheck('POST', '/orders', 201)
        .withResponseBody('{"id":42}')
        .withResponseHeaders(['Content-Type': 'application/json'])

def client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
client.postSync('/orders', null) { json([sku: 'A1']) }

assert check.lastRequest.header('Content-Type').startsWith('application/json')
assert check.lastRequest.bodyText == '{"sku":"A1"}'
server.shutdown()
```

`HttpClientTestDsl` adds a fluent endpoint and verification DSL, and `HttpClientTestTrait` brings both into a Spock specification. The fixtures are incubating: their API may change before 3.0.

---

## Upgrading

**From 2.0 to 2.1**, check:

- `HttpResponseException.message` no longer contains the response body. Read `responseBody`, `bodyAs(...)` or `problem`, or set `errorBodyPreviewChars`.
- The client's own size limit raises `ResponseTooLargeException` (still an `HttpResponseException` with status 413).
- Query values are encoded once: `&`, `=`, `/`, `?` and non-ASCII values now arrive intact.
- `addCookie` sends RFC 6265 cookies (no `$Version`).
- Multipart text and JSON parts (`MultipartPart.text/json`, DSL `content`) are sent as plain form fields, without a generated filename.

Fixed 2.0 bugs: an unknown name inside a request closure (for example your own constant) sent a stray `GET /<name>`; an oversized body on HTTP/2 or HTTP/3 surfaced as a retryable `IOException`; `@CompileStatic` callers could not use the request DSL.

**From 1.x**: 2.0 changed several security-relevant defaults (certificate verification, redirect checks, retries, exception types). Read the upgrade chapter in the documentation first.

---

## Documentation

The user guide is [`docs/groovy-httpclient-user-guide.pdf`](docs/groovy-httpclient-user-guide.pdf) (also as [`.docx`](docs/groovy-httpclient-user-guide.docx)); its source is [`docs/index.adoc`](docs/index.adoc). The Groovydoc is published as the `-javadoc` artifact, or run `./gradlew groovydoc`.

```bash
./gradlew asciidoctor       # HTML: build/docs/html5/index.html
./gradlew asciidoctorPdf    # PDF:  build/docs/pdf/groovy-httpclient-user-guide.pdf
```

---

## Building and testing

```bash
./gradlew build                                   # compile, unit and integration tests, jars
./gradlew releaseGate --rerun-tasks               # everything on JDK 27 and 25, feature gate, consumer smoke build
./gradlew test --tests '*CompressionSpec*'        # one spec
```

The unit specs use `MockHttpServer`; the integration specs run every feature against a real Jetty server over HTTP/1.1, HTTP/2 and HTTP/3. `releaseGate` checks that every catalogued feature has both, on both JDKs, and that a separate build can consume the published library and fixtures.

---

## Contributing

Issues and pull requests are welcome at [github.com/woodmawa/groovy-HttpClient](https://github.com/woodmawa/groovy-HttpClient). Please include a spec for any change; `./gradlew releaseGate --rerun-tasks` must pass.

---

## License

Copyright 2025 Softwood Consulting Ltd. Licensed under the [Apache License, Version 2.0](LICENSE).

---

## Support

- **Issues**: [GitHub Issues](https://github.com/woodmawa/groovy-HttpClient/issues)
- **Email**: will.woodman.softwood@outlook.com
