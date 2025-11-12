# GroovyHttpClient

A modern, thread-safe HTTP client for Groovy that leverages **Java 21 virtual threads** for high-performance, non-blocking HTTP operations.  
Built on top of Java’s `java.net.http.HttpClient` with enhanced resilience via a **Circuit Breaker pattern**, and full local testing support through an improved **MockHttpServer** with full **cookie management**.

---

## 🚀 Features

### GroovyHttpClient
- **Virtual Threads** – powered by Java 21 Loom for lightweight concurrency  
- **Thread-Safe** – safe to share between threads or actors  
- **Circuit Breaker** – detects repeated failures, prevents cascading errors  
- **Fluent Closure-Based API** – Groovy DSL for request customization  
- **HTTP/2 Support** – automatic negotiation with fallback to HTTP/1.1  
- **Sync & Async APIs** – `getSync()` or `get()` for non-blocking calls  
- **Full Cookie Management** – automatic persistence and manual control  
- **Custom SSL/TLS** – for testing or secure production contexts  
- **Complete HTTP Verb Support** – GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS  

### MockHttpServer
- **Lightweight HTTP Server** for integration and unit tests  
- **Per-Request Configuration** (method, path, headers, delay, status, body)  
- **Header & Cookie Validation** – test request integrity easily  
- **Multi-Value Headers** via `Map<String,List<String>>`  
- **Response Cookie Simulation** with `Set-Cookie` headers  
- **Response Delay Simulation** for timeout testing  
- **Graceful Lifecycle** (`init()`, `shutdown()`)  

---

## 🚀 Quick Start Example

```groovy
import org.softwood.http.GroovyHttpClient
import org.softwood.test.MockHttpServer

def server = new MockHttpServer()
server.init()
server.addRequestCheck("GET", "/hello", 200)
      .withResponseBody('{"msg":"hi"}')

def client = new GroovyHttpClient("http://localhost:${server.port}")

def response = client.getSync("/hello")
println response.body   // → {"msg":"hi"}

server.shutdown()
```

---

## 🍪 Cookie Handling

### Automatic Cookie Management
Cookies are automatically stored and reused between requests.

```groovy
def client = new GroovyHttpClient("http://localhost:${server.port}")
client.getSync("/login")         // receives Set-Cookie
client.getSync("/profile")       // cookie sent automatically
```

### Manual Cookie Control
You can add, remove, and clear cookies directly:

```groovy
client.addCookie("session", "abc123")
client.removeCookie("session")
client.clearCookies()
```

### Inspecting Cookies
```groovy
println client.getCookie("session")?.value
println client.getCookies()  // list of HttpCookie objects
```

---

## 🧪 MockHttpServer Cookie Testing

```groovy
def server = new MockHttpServer()
server.init()

server.addRequestCheck("GET", "/secure", 200)
      .withExpectedCookies([auth: "token123"])
      .withResponseCookies([session: "new456"])
      .withResponseBody('{"ok":true}')

def client = new GroovyHttpClient("http://localhost:${server.port}")
client.addCookie("auth", "token123")

def response = client.getSync("/secure")

assert response.statusCode == 200
assert client.getCookie("session").value == "new456"

server.shutdown()
```

---

## ⚡ Circuit Breaker

```groovy
def client = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(5),
    Duration.ofSeconds(15),
    3,        // failure threshold
    10000     // reset timeout
)

try {
    client.getSync("/data")
} catch (GroovyHttpClient.CircuitOpenException e) {
    println "Service temporarily unavailable"
}
```

---

## 🧩 Example with Headers and Cookies

```groovy
def client = new GroovyHttpClient("http://localhost:${server.port}")
client.withHeader("X-API-Key", "123")
      .addCookie("auth", "abc")

def response = client.postSync("/submit", '{"data":42}') {
    header "Content-Type", "application/json"
}
```

---

## 🧱 Testing with MockHttpServer

```groovy
def server = new MockHttpServer()
server.init()

server.addRequestCheck("GET", "/multi", 200)
      .withResponseHeaders([
          "X-Test": ["val1", "val2"],
          "Content-Type": ["application/json"]
      ])
      .withResponseCookies([session: "xyz"])
      .withResponseBody('{"ok":true}')

def client = new GroovyHttpClient("http://localhost:${server.port}")
def response = client.getSync("/multi")

assert response.getHeader("X-Test") == "val1"
assert client.getCookie("session").value == "xyz"

server.shutdown()
```

---

## 🧰 Best Practices

1. Reuse client instances  
2. Always call `close()` after use  
3. Catch `CircuitOpenException` and `HttpResponseException`  
4. Use async (`get()`, `post()`) for concurrency  
5. Use `MockHttpServer` for local tests  
6. Use `clearCookies()` between tests for isolation  

---

## 🔧 Troubleshooting

| Issue | Cause | Solution |
|--------|--------|----------|
| Cookie mismatch | Wrong or missing cookie | Use `.withExpectedCookies()` in tests |
| Timeout | Too short `requestTimeout` | Increase request timeout |
| Circuit open | Too many failures | Wait or increase `resetTimeoutMs` |
| Port conflicts | Reusing fixed port | Use auto-port (default) |
| Header mismatch | Case sensitivity | All headers matched case-insensitively |

---

## 🧵 Thread Safety
- Built entirely on **virtual threads**  
- Thread-safe `CookieManager` and circuit breaker  
- Each request fully isolated  

---

## 🪪 License
See your project’s `LICENSE` file.
