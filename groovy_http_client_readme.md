# Groovy HTTP Client

A modern, Groovy-friendly HTTP client built on Java 21’s `HttpClient` and virtual threads.  
It provides a clean DSL-style API for making HTTP/1.1 and HTTP/2 requests, automatic cookie handling,  
and optional integration with a powerful `SecurityConfig` for production-grade hardening.

---

## 🚀 Features

- 🔧 Simple, fluent Groovy DSL (`client.get("/api") { builder -> builder.header("X", "test") }`)
- ⚙️ Built on Java 21 Virtual Threads (`Thread.ofVirtual()`)
- 🍪 Full cookie management (automatic and manual)
- 🧱 Circuit breaker for resilience
- 🔐 Security configuration via `SecurityConfig`
- 🧪 Testable via lightweight `MockHttpServer`
- 🌐 Supports HTTP/2 and asynchronous requests
- 🧰 Groovy closures with DSL safety
- 🕵️‍♂️ Supports connection, request, and sync timeouts

---

## 🏗️ Quick Start

```groovy
import org.softwood.http.GroovyHttpClient
import org.softwood.http.SecurityConfig

// Create a secure default configuration (TLS + safe defaults)
def cfg = SecurityConfig.production("https://api.example.com")

// Instantiate the client
def client = new GroovyHttpClient(cfg)

// Perform a GET request
def response = client.getSync("/users") { builder ->
    builder.header("Accept", "application/json")
}

println "Status: ${response.statusCode}"
println "Body: ${response.body}"
```

---

## ⚙️ Security Configuration

All client security and environment behavior is centralized in `SecurityConfig`.  
This makes it easy to toggle between production, staging, and testing configurations  
without changing client code.

### ✅ Basic Profiles

```groovy
import org.softwood.http.SecurityConfig

// Production – strict TLS, limited redirects, cookie policy = ORIGINAL_SERVER
def prodCfg = SecurityConfig.production("https://api.example.com")

// Staging – TLS enforced, more logging
def stageCfg = SecurityConfig.staging("https://staging.example.com")

// Testing – accepts all cookies, allows HTTP, absolute URLs, and self-signed certs
def testCfg = SecurityConfig.testing("http://localhost:8080")
```

Each profile defines safe defaults for:
| Property | Description |
|-----------|-------------|
| `insecureAllowed` | Whether trust-all SSL is permitted |
| `allowAbsoluteUrls` | Whether fully-qualified URLs can be used |
| `cookiePolicy` | Cookie acceptance policy |
| `connectTimeout`, `requestTimeout` | Default timeouts |
| `allowedTlsProtocols` | TLS versions enforced |
| `maxResponseBytes` | Limit to prevent large-body responses |
| `failureThreshold`, `resetTimeoutMs` | Circuit breaker parameters |

---

## 🔐 Advanced Example

```groovy
import org.softwood.http.GroovyHttpClient
import org.softwood.http.SecurityConfig
import java.time.Duration

def cfg = SecurityConfig.production("https://api.mycorp.internal")
    .withOverrides([
        connectTimeout: Duration.ofSeconds(3),
        requestTimeout: Duration.ofSeconds(10),
        enableLogging : true
    ])

def client = new GroovyHttpClient(cfg)

// Custom POST with headers and cookies
def response = client.postSync("/auth/login", '{"user":"admin"}') { builder ->
    builder.header("Content-Type", "application/json")
    builder.cookie("session", "abc123")
}

println "Response: ${response.body}"
```

---

## 🍪 Cookie Handling

The client automatically manages cookies using an internal `CookieManager`.

### Manual Cookie Management

```groovy
client.addCookie("session", "abc123")
client.addCookie("auth", "tokenXYZ")

def cookies = client.getCookies()
println cookies // → [session=abc123, auth=tokenXYZ]

client.removeCookie("auth")
client.clearCookies()
```

Cookies persist between requests within the same client instance  
and obey the configured `CookiePolicy` in `SecurityConfig`.

---

## 🧩 Using MockHttpServer for Tests

For testing, use the lightweight in-memory `MockHttpServer`.

### Example

```groovy
import org.softwood.test.MockHttpServer
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient

def server = new MockHttpServer()
server.init()
server.addRequestCheck("GET", "/users", 200)
      .withResponseBody('{"users":["alice","bob"]}')

def cfg = SecurityConfig.testing("http://localhost:${server.port}")
def client = new GroovyHttpClient(cfg)

def response = client.getSync("/users")

assert response.statusCode == 200
assert response.body.contains("alice")

server.shutdown()
```

This pattern is used throughout the project’s Spock specs to test:
- GET/POST/PATCH request handling
- Cookies and headers
- Concurrent (HTTP/2-like) requests
- Circuit breaker behavior

---

## 🧰 Circuit Breaker

To protect against repeatedly calling failing services, the client includes an internal circuit breaker.

| Property | Description |
|-----------|-------------|
| `failureThreshold` | Number of failures before circuit opens |
| `resetTimeoutMs` | How long before circuit auto-closes |
| `CircuitOpenException` | Thrown when the circuit is open |

---

## 🔄 Asynchronous and Synchronous Requests

| Method | Description |
|---------|-------------|
| `get()`, `post()`, etc. | Asynchronous (returns `CompletableFuture`) |
| `getSync()`, `postSync()` | Synchronous (blocks until complete) |

### Example

```groovy
// Async
def future = client.get("/data")
future.thenAccept { resp -> println "Got ${resp.body}" }

// Sync
def response = client.postSync("/data", '{"test":true}')
```

---

## 🧱 Secure Defaults and Hardening Recommendations

When deploying to production:

| Setting | Recommendation |
|----------|----------------|
| `cookiePolicy` | `CookiePolicy.ACCEPT_ORIGINAL_SERVER` |
| `insecureAllowed` | `false` |
| `allowAbsoluteUrls` | `false` |
| `allowedTlsProtocols` | `["TLSv1.3"]` |
| `connectTimeout` | `<= 5s` |
| `requestTimeout` | `<= 30s` |
| `maxResponseBytes` | `<= 10 MB` |
| `enforceHostnameVerification` | `true` |
| `enableLogging` | `false` in production, `true` in staging |

---

## 🧪 Running the Tests

The test suite uses Spock and `MockHttpServer`.

```bash
./gradlew test
```

Tests include:
- Cookie handling and isolation
- Circuit breaker open/reset
- Concurrent HTTP/2-like behavior
- Security policy enforcement

---

## 🔍 Troubleshooting

| Symptom | Possible Cause |
|----------|----------------|
| `MissingMethodException` for `header()` | Use `{ builder -> builder.header("...") }` form |
| Cookies not sent | Check cookie policy (`ACCEPT_ALL` for testing) |
| `HTTP error: 400` | Mock server rejecting expected cookie/header |
| Timeout | Increase `requestTimeout` in `SecurityConfig` |

---

## 📄 License

Apache 2.0 (or your project’s applicable license)

---

## 🧠 Summary

`GroovyHttpClient` now provides:

✅ Fluent Groovy API  
✅ Modern async & sync operations  
✅ Full cookie lifecycle  
✅ Configurable security policies  
✅ Circuit breaker resilience  
✅ Safe and testable design  

Use `SecurityConfig` for **consistent, centralized control**  
of all runtime security and connection behavior.
