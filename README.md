# Groovy HTTP Client

A modern, production-ready HTTP client for Groovy and Java applications, built on Java's standard `HttpClient` API with **virtual threads**, comprehensive **security controls**, and a powerful **testing framework**.

[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Groovy](https://img.shields.io/badge/Groovy-4.0+-orange.svg)](https://groovy-lang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## 🚀 Key Features

### **Modern Architecture**
- **Virtual Thread Executor** — Handle millions of concurrent requests with minimal overhead (Java 21+)
- **HTTP/2 Support** — Automatic protocol negotiation with multiplexing
- **Zero Magic** — Wraps Java's standard `HttpClient` — no reflection, no bytecode manipulation

### **Groovy-First Design**
- **Fluent DSL** — Natural, expressive syntax for requests, headers, and multipart uploads
- **Automatic JSON/XML Parsing** — Built-in content-type aware response handling
- **Closure-Based Configuration** — Groovy-idiomatic request building

### **Production-Ready Resilience**
- **Circuit Breaker** — Automatic failure detection with CLOSED → OPEN → HALF_OPEN states
- **Retry Policies** — Exponential backoff with jitter for transient failures
- **Request/Response Interceptors** — Logging, metrics, and custom processing

### **Security by Default**
- **Environment-Aware Profiles** — `testing()`, `development()`, `production()` configurations
- **SSRF Protection** — Host allow-listing and absolute URL controls
- **TLS Configuration** — Custom trust stores, hostname verification, protocol selection
- **Configurable Timeouts** — Connect, request, and synchronous operation limits

### **Comprehensive Testing Framework**
- **MockHttpServer** — Lightweight, dependency-free embedded test server
- **HttpClientTestDsl** — Fluent DSL for endpoint mocking and verification
- **HttpClientTestTrait** — Spock trait for seamless test integration
- **Request Recording** — Rich assertions for headers, body, cookies, and query parameters

---

## 📦 Quick Start

### Installation

**Gradle:**
```groovy
dependencies {
    implementation 'org.softwood:groovy-HttpClient:1.5.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>org.softwood</groupId>
    <artifactId>groovy-HttpClient</artifactId>
    <version>1.5.0</version>
</dependency>
```

### Basic Usage

```groovy
import org.softwood.http.GroovyHttpClient

// Create client
def client = new GroovyHttpClient("https://api.github.com")

// Make GET request
def response = client.getSync("/users/octocat")

println "Status: ${response.statusCode}"
println "User: ${response.json.name}"

// POST with JSON
def userData = [name: "Alice", email: "alice@example.com"]
def created = client.postSync("/users", null) {
    json(userData)
    header "Authorization", "Bearer ${token}"
}
```

---

## 🎯 Core Capabilities

### HTTP Methods

```groovy
// Synchronous requests
def response = client.getSync("/resource")
def response = client.postSync("/resource", body)
def response = client.putSync("/resource", body)
def response = client.patchSync("/resource", body)
def response = client.deleteSync("/resource")

// Asynchronous requests
client.get("/resource").thenApply { response ->
    println response.json
}
```

### Request Configuration

```groovy
def response = client.getSync("/api/users") {
    // Query parameters
    query(
        page: 1,
        per_page: 50,
        status: ["active", "pending"]
    )
    
    // Headers
    header "Authorization", "Bearer ${token}"
    header "Accept", "application/json"
    
    // Timeout for this request
    timeout(Duration.ofSeconds(5))
}
```

### Multipart Uploads

**DSL Style:**
```groovy
def response = client.postMultipartSync("/upload") {
    part {
        name "document"
        filename "report.pdf"
        contentType "application/pdf"
        data fileBytes
    }
    
    part {
        name "metadata"
        contentType "application/json"
        content '{"title": "Q4 Report"}'
    }
    
    header "Authorization", "Bearer ${token}"
}
```

**Static API Style:**
```groovy
import org.softwood.http.MultipartPart

def parts = [
    MultipartPart.bytes("file", "doc.pdf", "application/pdf", fileBytes),
    MultipartPart.json("metadata", [title: "Q4 Report"]),
    MultipartPart.text("description", "Financial report")
]

def response = client.postMultipartSync("/upload", parts)
```

### File Downloads

```groovy
// Download as bytes
byte[] fileData = client.downloadBytesSync("/files/document.pdf") {
    header "Authorization", "Bearer ${token}"
}

// Save to disk
new File("downloaded.pdf").bytes = fileData
```

---

## 🔒 Security Configuration

### Environment Profiles

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient

// Testing - Trust all SSL, permissive settings
def config = SecurityConfig.testing("http://localhost:8080")
def client = new GroovyHttpClient(config)

// Development - Relaxed timeouts, detailed logging
def config = SecurityConfig.development("https://dev-api.example.com")

// Production - Strict security, optimized settings
def config = SecurityConfig.production("https://api.example.com")
```

### Custom Configuration

```groovy
def config = SecurityConfig.production("https://api.example.com")
    .withOverrides([
        // Timeouts
        connectTimeout: Duration.ofSeconds(5),
        requestTimeout: Duration.ofSeconds(10),
        
        // Circuit Breaker
        failureThreshold: 5,
        resetTimeoutMs: 30_000,
        
        // SSRF Protection
        allowedHosts: ["api.example.com", "cdn.example.com"],
        allowAbsoluteUrls: false,
        
        // TLS
        allowedTlsProtocols: ["TLSv1.3"],
        enforceHostnameVerification: true,
        
        // Retry Policy
        retryPolicy: new RetryPolicy.Config()
            .maxAttempts(3)
            .backoffExponential(200, 2000, true)
            .on5xx(true)
            .build()
    ])

def client = new GroovyHttpClient(config)
```

---

## 🧪 Testing Framework

### MockHttpServer

```groovy
import org.softwood.test.MockHttpServer
import spock.lang.Specification

class ApiClientSpec extends Specification {
    
    MockHttpServer server
    ApiClient client
    
    def setup() {
        server = new MockHttpServer()
        server.init()
        client = new ApiClient("http://localhost:${server.port}")
    }
    
    def cleanup() {
        server.shutdown()
    }
    
    def "should fetch user data"() {
        given:
        server.addRequestCheck("GET", "/users/123", 200)
            .withResponseBody('{"id": 123, "name": "Alice"}')
        
        when:
        def user = client.getUser(123)
        
        then:
        user.id == 123
        user.name == "Alice"
    }
}
```

### HttpClientTestDsl

```groovy
import org.softwood.http.HttpClientTestDsl
import spock.lang.Specification

class UserServiceSpec extends Specification {
    
    HttpClientTestDsl testDsl
    UserService service
    
    def setup() {
        testDsl = HttpClientTestDsl.create()
        testDsl.start()
        service = new UserService(testDsl.baseUrl)
    }
    
    def cleanup() {
        testDsl.stop()
    }
    
    def "should create user"() {
        given: "mock endpoint"
        testDsl.when("/users").respondWith {
            status 201
            json([id: 456, name: "Bob"])
        }
        
        when: "creating user"
        def user = service.createUser("Bob", "bob@example.com")
        
        then: "user is created"
        user.id == 456
        
        and: "request was correct"
        testDsl.verify("/users") {
            wasCalled(1)
            withMethod("POST")
            withHeader("Content-Type", "application/json")
            withJsonBody { json ->
                json.name == "Bob" &&
                json.email == "bob@example.com"
            }
        }
    }
}
```

### HttpClientTestTrait

```groovy
import org.softwood.http.HttpClientTestTrait
import spock.lang.Specification

class ProductServiceSpec extends Specification implements HttpClientTestTrait {
    
    ProductService service
    
    def setup() {
        service = new ProductService(testServerBaseUrl)
    }
    
    def "should list products"() {
        given:
        mockEndpoint("/products").respondWith {
            status 200
            json([
                [id: 1, name: "Widget"],
                [id: 2, name: "Gadget"]
            ])
        }
        
        when:
        def products = service.listProducts()
        
        then:
        products.size() == 2
        verifyEndpoint("/products").wasCalled(1)
    }
}
```

---

## 🛡️ Resilience Patterns

### Circuit Breaker

```groovy
import org.softwood.http.SecurityConfig
import org.softwood.http.GroovyHttpClient

// Configure circuit breaker
def config = SecurityConfig.production("https://api.example.com")
    .withOverrides([
        failureThreshold: 5,      // Open after 5 failures
        resetTimeoutMs: 30_000    // Try recovery after 30 seconds
    ])

def client = new GroovyHttpClient(config)

try {
    def response = client.getSync("/data")
} catch (GroovyHttpClient.CircuitOpenException e) {
    println "Service unavailable - circuit is open"
    // Fallback to cache or alternative
}
```

### Retry Policy

```groovy
import org.softwood.http.GroovyHttpClient.RetryPolicy

def retryPolicy = new RetryPolicy.Config()
    .maxAttempts(5)
    .backoffExponential(200, 10_000, true)  // 200ms to 10s with jitter
    .onTimeouts(true)
    .on5xx(true)
    .onIoExceptions(true)
    .build()

def config = SecurityConfig.production("https://api.example.com")
    .withOverrides([retryPolicy: retryPolicy])

def client = new GroovyHttpClient(config)

// Automatically retries on failures
def response = client.getSync("/flaky-endpoint")
```

### Request Interceptors

```groovy
// Add logging interceptor
client.addInterceptor { ctx, next ->
    def start = System.currentTimeMillis()
    println "[${ctx.method}] ${ctx.uri}"
    
    next.call(ctx).whenComplete { response, error ->
        def elapsed = System.currentTimeMillis() - start
        if (error) {
            println "✗ Failed after ${elapsed}ms: ${error.message}"
        } else {
            println "✓ ${response.statusCode} in ${elapsed}ms"
        }
    }
}

// Add authentication interceptor
client.addInterceptor { ctx, next ->
    ctx.builder.header("Authorization", "Bearer ${getToken()}")
    next.call(ctx)
}
```

---

## ⚡ Virtual Threads

Built on **Java 21 virtual threads** for efficient, scalable concurrency:

```groovy
// Launch thousands of concurrent requests
def futures = (1..10_000).collect { id ->
    client.get("/users/${id}").thenApply { it.json }
}

// Wait for all to complete
def users = CompletableFuture.allOf(*futures).thenApply {
    futures.collect { it.join() }
}.get()

println "Fetched ${users.size()} users concurrently"
```

Virtual threads enable:
- **Millions of concurrent requests** without thread pool exhaustion
- **Simple synchronous code** without blocking platform threads
- **Automatic scaling** — no manual thread pool tuning required

---

## 📚 Documentation

Comprehensive documentation is available in the `docs/` directory:

- **[Introduction](docs/chapters/01-introduction.adoc)** — Overview and getting started
- **[Architecture](docs/chapters/02-architecture.adoc)** — Virtual threads and design patterns
- **[Security Configuration](docs/chapters/03-security-config.adoc)** — Environment profiles and security controls
- **[URL Builder](docs/chapters/04-url-builder.adoc)** — Constructing complex URLs
- **[GroovyHttpClient](docs/chapters/05-groovy-http-client.adoc)** — Core client usage
- **[Multipart Uploads](docs/chapters/06-multipart-uploads.adoc)** — File uploads and multipart forms
- **[Circuit Breaker](docs/chapters/07-circuit-breaker.adoc)** — Resilience and failure handling
- **[Testing Framework](docs/chapters/08-testing-mock-server.adoc)** — MockHttpServer guide
- **[Testing DSL](docs/chapters/09-testing-dsl.adoc)** — HttpClientTestDsl reference
- **[Testing Trait](docs/chapters/10-testing-trait.adoc)** — Spock integration
- **[Examples](docs/chapters/11-examples.adoc)** — Real-world usage patterns

### Building Documentation

```bash
# Generate HTML documentation
./gradlew asciidoctor

# Generate PDF documentation
./gradlew asciidoctorPdf

# Output: build/docs/html5/index.html
# Output: build/docs/pdf/index.pdf
```

---

## 🧩 Requirements

| Component | Version |
|-----------|---------|
| **Java** | 21 or higher (for virtual threads) |
| **Groovy** | 4.0 or higher |
| **Build Tool** | Gradle 8+ or Maven 3.8+ |

> **Note:** Virtual threads are production-ready in Java 21+. They were preview features in Java 19-20.

---

## 🏗️ Architecture Highlights

### Virtual Thread Executor

```groovy
// Custom executor using virtual threads
private ExecutorService createVirtualThreadExecutor() {
    def factory = Thread.ofVirtual()
        .name("http-client-", 0)
        .factory()
    
    return Executors.newThreadPerTaskExecutor(factory)
}
```

### Circuit Breaker States

```
CLOSED (normal operation)
   ↓ (failureThreshold exceeded)
OPEN (fail-fast, reject requests)
   ↓ (resetTimeout elapsed)
HALF_OPEN (probe with single request)
   ↓ (success)           ↓ (failure)
CLOSED                  OPEN
```

### Request Processing Pipeline

```
Request → Interceptors → Circuit Breaker → Retry Policy → HttpClient → Response
```

---

## 🧪 Testing

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Suite

```bash
# Client tests
./gradlew test --tests "org.softwood.http.clientTests.*"

# Testing framework tests
./gradlew test --tests "org.softwood.http.testingFrameworkTests.*"

# Specific test class
./gradlew test --tests GroovyHttpClientSpec
```

### Test Coverage

```bash
# Generate coverage report
./gradlew test jacocoTestReport

# Open build/reports/jacoco/test/html/index.html
```

### Test Examples Location

All test examples are in `src/test/groovy/org/softwood/http/`:

```
clientTests/
├── GroovyHttpClientSpec.groovy          # Core client functionality
├── GroovyHttpClientMultipartSpec.groovy # Multipart uploads
├── CircuitBreakerSpec.groovy            # Circuit breaker behavior
└── RetryPolicySpec.groovy               # Retry logic

testingFrameworkTests/
├── HttpClientTestDslSpec.groovy         # Test DSL
├── MockHttpServerSpec.groovy            # Mock server
└── HttpClientTestTraitSpec.groovy       # Spock trait
```

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Write tests for your changes
4. Ensure all tests pass (`./gradlew test`)
5. Commit your changes (`git commit -m 'Add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

---

## 📝 License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](LICENSE) file for details.

```
Copyright 2025 Softwood Consulting Ltd

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 🙏 Acknowledgments

- Built on Java's standard `HttpClient` API ([JEP 321](https://openjdk.org/jeps/321))
- Virtual threads from Project Loom ([JEP 444](https://openjdk.org/jeps/444))
- Inspired by modern HTTP client libraries across languages
- Groovy community for language support and feedback

---

## 📞 Support

- **Documentation**: See `docs/` directory or build with `./gradlew asciidoctor`
- **Issues**: [GitHub Issues](https://github.com/softwood/groovy-HttpClient/issues)
- **Email**: will@softwood.org
- **Source**: [GitHub Repository](https://github.com/softwood/groovy-HttpClient)

---

**Made with ❤️ by Softwood Consulting Ltd**
