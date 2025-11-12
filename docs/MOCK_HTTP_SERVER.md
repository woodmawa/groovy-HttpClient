# MockHttpServer — Lightweight HTTP Test Server

**Package:** `org.softwood.test`  
**Purpose:** A lightweight, dependency-free mock HTTP server for Groovy and Java tests.  
Built on top of `com.sun.net.httpserver.HttpServer`, it provides easy request validation, response simulation, and cookie handling.

---

## 🚀 Quick Start Example

```groovy
import org.softwood.test.MockHttpServer
import org.softwood.http.GroovyHttpClient

def server = new MockHttpServer()
server.init()

// Configure a simple endpoint
server.addRequestCheck("GET", "/users", 200)
    .withResponseBody('{"users":["user1","user2"]}')

def client = new GroovyHttpClient("http://localhost:${server.port}")

def response = client.getSync("/users")

assert response.statusCode == 200
assert response.body == '{"users":["user1","user2"]}'

server.shutdown()
```

✅ Starts a local mock server on a random port  
✅ Registers a `/users` route  
✅ Verifies response content and code  
✅ Shuts down cleanly after test

---

## 🧩 Core Features

| Feature | Description |
|----------|-------------|
| **Dynamic routes** | Add handlers for any HTTP method or path. |
| **Header assertions** | Match expected headers with `.withRequestHeaders()`. |
| **Cookie handling** | Parse and validate cookies; send `Set-Cookie` responses. |
| **Custom responses** | Set status, headers, and body content. |
| **Delays** | Simulate latency via `.withDelay(ms)`. |
| **Simple teardown** | Call `shutdown()` to stop the server cleanly. |

---

## ⚙️ API Overview

### Creating and Starting the Server

```groovy
def server = new MockHttpServer()
server.init()    // automatically starts and binds to an available port
println "Server running on port ${server.port}"
```

### Adding Request Checks

```groovy
server.addRequestCheck("POST", "/api/items", 201)
    .withRequestHeaders(["Content-Type": "application/json"])
    .withResponseBody('{"result":"created"}')
```

Each request check validates:
- HTTP method (`GET`, `POST`, etc.)
- Headers (optional)
- Cookies (optional)
- Response body and headers

### Full Example with Cookies

```groovy
server.addRequestCheck("GET", "/login", 200)
    .withExpectedCookies([session: "12345"])
    .withResponseCookies([session: "67890"])
    .withResponseBody('{"ok":true}')
```

- If the incoming request is missing or mismatches a cookie, a `400` error is returned automatically.  
- The response sets a new cookie for subsequent requests.

---

## 🧠 Example: Using in Spock Tests

```groovy
def "should support cookie validation"() {
    given:
    def server = new MockHttpServer()
    server.init()
    server.addRequestCheck("GET", "/secure", 200)
            .withExpectedCookies([auth: "abc123"])
            .withResponseBody('{"ok":true}')

    def client = new GroovyHttpClient("http://localhost:${server.port}")
    client.addCookie("auth", "wrongValue")

    when:
    client.getSync("/secure")

    then:
    def ex = thrown(java.util.concurrent.ExecutionException)
    ex.cause instanceof GroovyHttpClient.HttpResponseException
    ex.cause.statusCode == 400
    ex.cause.message.contains("Cookie mismatch")

    cleanup:
    server.shutdown()
}
```

---

## 🧹 Clean Shutdown

Always stop the server after each test to release the bound port:
```groovy
server.shutdown()
```

This ensures clean teardown between test cases.

---

## 🧪 Advanced Use: Multi-Request Mocking

You can register multiple checks before calling `init()`:
```groovy
def server = new MockHttpServer()
server.addRequestCheck("GET", "/a", 200).withResponseBody("A")
server.addRequestCheck("GET", "/b", 200).withResponseBody("B")
server.init()
```

All registered routes are automatically available once `init()` is called.

---

## 🧱 Design Notes

- Backed by `com.sun.net.httpserver.HttpServer` → no external dependencies.
- Thread-safe via `Executors.newCachedThreadPool()`.
- Ideal for integration testing or unit tests of HTTP clients.
- Default behavior:
  - `405` if HTTP method mismatched.
  - `400` if cookies mismatched.
  - `500` if headers mismatched.
- Each request runs in its own thread — suitable for concurrent testing.

---

## 🧩 Dependencies

None — uses only the JDK.  
Works with:
- Java 17+  
- Groovy 4+  
- Spock 2+  

---

## 🧰 Integration Tips

- Pair with `GroovyHttpClient` for full end-to-end HTTP test simulation.  
- Use `withDelay(ms)` to test timeouts or retry logic.  
- Chain builder methods fluently to create concise test setups.

---

## 📦 Example: Combined Setup

```groovy
def server = new MockHttpServer().init()
server.addRequestCheck("POST", "/auth", 200)
    .withResponseCookies([session: "abc123"])
    .withResponseBody('{"authenticated":true}')

def client = new GroovyHttpClient("http://localhost:${server.port}")
def response = client.postSync("/auth", '{"user":"test"}')

assert response.statusCode == 200
assert client.getCookie("session").value == "abc123"

server.shutdown()
```
