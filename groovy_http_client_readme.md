# GroovyHttpClient

A modern, thread-safe HTTP client for Groovy that leverages **Java 21 virtual threads** for high-performance, non-blocking HTTP operations.  
Built on top of Java’s `java.net.http.HttpClient` with enhanced resilience via a **Circuit Breaker pattern** and full local testing support through a **MockHttpServer**.

---

## 🚀 Features

### GroovyHttpClient
- **Virtual Threads** – powered by Java 21 Loom for lightweight concurrency  
- **Thread-Safe** – safe to share between threads or actors  
- **Circuit Breaker** – detects repeated failures, prevents cascading service errors  
- **Fluent, Closure-Based API** – readable request customization  
- **HTTP/2 Support** – automatic negotiation with fallback to HTTP/1.1  
- **Sync and Async APIs** – `getSync()` for blocking, `get()` for async  
- **Custom SSL/TLS** – for test or production contexts  
- **Full HTTP Verb Support** – GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS  

### MockHttpServer
- **Lightweight Test Server** for local integration tests  
- **Per-Request Configuration** (method, path, headers, delay, status, body)  
- **Multi-Value Headers** supported via `Map<String, List<String>>`  
- **Response Delay Simulation** via `.withDelay(ms)`  
- **Graceful Lifecycle** (`start()`, `stop()`, automatic port assignment)  

---

## 🚀 Quick Start Example

```groovy
import org.softwood.http.GroovyHttpClient
import org.softwood.test.MockHttpServer

// Start mock server on auto-assigned port
def server = new MockHttpServer(0)
server.start()
server.addRequestCheck("GET", "/hello", 200, '{"msg":"hi"}')

def client = new GroovyHttpClient("http://localhost:${server.port}")

try {
    // Synchronous GET
    println client.getSync("/hello")

    // Asynchronous GET
    client.get("/hello").thenAccept { resp ->
        println "Async Response: $resp"
    }.join()
} finally {
    client.close()
    server.stop()
}
```

---

## 📦 Installation

Add to your project under:

```
src/main/groovy/org/softwood/http/GroovyHttpClient.groovy
src/test/groovy/org/softwood/test/MockHttpServer.groovy
```

Or publish locally using Gradle:

```bash
./gradlew publishToMavenLocal
```

Then include it in your `build.gradle`:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation "org.softwood:groovy-HttpClient:1.0-SNAPSHOT"
}
```

---

## ⚙️ Basic Usage

```groovy
def client = new GroovyHttpClient("https://api.example.com")

// Custom timeouts
def client2 = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(5),
    Duration.ofSeconds(15)
)
```

---

## 🧱 Using the Builder

```groovy
def client = GroovyHttpClient.Builder()
    .setBaseUrl("https://api.example.com")
    .setConnectionTimeout(Duration.ofSeconds(10))
    .setRequestTimeout(Duration.ofMinutes(1))
    .setCircuitBreakerFailureThreshold(5)
    .setCircuitBreakerResetTimeout(10000)
    .build()
```

---

## 🧩 Using Options Map

```groovy
def options = [
    connectionTimeout: Duration.ofSeconds(10),
    requestTimeout: Duration.ofMinutes(2),
    circuitBreakerFailureThreshold: 7,
    circuitBreakerResetTimeout: 15000
]
def client = GroovyHttpClient.createWithOptions("https://api.example.com", options)
```

---

## 🌐 Making Requests

### Synchronous

```groovy
def result = client.getSync("/posts/1")
println result
```

### Asynchronous

```groovy
client.get("/users").thenAccept { resp ->
    println "Response: $resp"
}
```

### POST Example

```groovy
def json = '{"title":"My Post","body":"Hello","userId":1}'
def response = client.postSync("/posts", json) {
    header "Content-Type", "application/json"
}
```

---

## ⚡ Circuit Breaker

```groovy
def client = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(5),
    Duration.ofSeconds(15),
    3, // fail threshold
    10000 // reset after 10s
)

try {
    client.getSync("/data")
} catch (GroovyHttpClient.CircuitOpenException e) {
    println "Service temporarily unavailable"
}
```

---

## 🧪 Testing with MockHttpServer

```groovy
def server = new MockHttpServer(0)
server.start()

server.addRequestCheck(
    "GET",
    "/multi",
    200,
    '{"ok":true}',
    [ "X-Request": ["val1", "val2"] ],
    [ "X-Response": ["res1", "res2"] ],
    500 // delay
)

def client = new GroovyHttpClient("http://localhost:${server.port}")
println client.getSync("/multi")
server.stop()
```

---

## 🧵 Thread Safety

- Built on **virtual threads**
- Each request isolated  
- Circuit breaker uses **atomic state**  
- Clients are **safe to share**  

---

## 🧰 Best Practices

1. Reuse client instances  
2. Always call `close()`  
3. Catch `CircuitOpenException` and `HttpResponseException`  
4. Use async for concurrent loads  
5. Log or monitor circuit breaker transitions  
6. For tests, isolate with `MockHttpServer`

---

## 🔧 Troubleshooting

- **Timeouts**: Ensure `requestTimeout` exceeds any `MockHttpServer` delay.  
- **Circuit Open**: Reset after `circuitBreakerResetTimeout`.  
- **Port Conflicts**: Use `0` for auto-port.  
- **Headers**: Use `Map<String, List<String>>`.  
- **Thread Leaks**: Always `close()` clients.

---

## 🪪 License

See your project’s `LICENSE` file.