# GroovyHttpClient

A modern, thread-safe HTTP client for Groovy that leverages Java 21's virtual threads for highly scalable, non-blocking HTTP operations. Built on top of Java's HttpClient API with added resilience through the Circuit Breaker pattern.

## Features

- **Virtual Thread-Based**: Uses Java 21 virtual threads for efficient, non-blocking I/O operations
- **Thread-Safe**: Safe for concurrent use across multiple threads
- **Circuit Breaker Pattern**: Automatic failure detection and service protection
- **Fluent API**: Groovy closure-based configuration for clean, readable code
- **HTTP/2 Support**: Automatic HTTP/2 with HTTP/1.1 fallback
- **Async & Sync Operations**: Choose between asynchronous (CompletableFuture) or synchronous execution
- **SSL/TLS Flexibility**: Configurable SSL contexts for testing and production
- **Full HTTP Method Support**: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS

## Installation

Add the `GroovyHttpClient.groovy` file to your project under the `org.softwood.http` package.

or use Gradle publishToMavenLocal target to put the jar in your local ~./m2 repository and then just add mavenLocal() 
to your repositories block  before mavenCentral()

```groovy
repositories {
    maven {
        url = uri("${project.buildDir}/repo")
    }
    mavenLocal() // optional, for ~/.m2/repository
    mavenCentral()
}
```

or include the jar directly from the /build/repo/org/softwood/groovy-HttpClient/1.0-SNAPSHOT in your client project dependencies 

## Basic Usage

### Creating a Client

```groovy
import org.softwood.http.GroovyHttpClient

// Simple client with default settings
def client = new GroovyHttpClient("https://api.example.com")

// Client with custom timeouts
def client = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(5),  // connect timeout
    Duration.ofSeconds(15)  // request timeout
)
```

### Simple GET Request (Synchronous)

```groovy
def client = new GroovyHttpClient("https://jsonplaceholder.typicode.com")

try {
    String response = client.getSync("/posts/1")
    println response
} finally {
    client.close()
}
```

### Simple POST Request (Synchronous)

```groovy
def client = new GroovyHttpClient("https://jsonplaceholder.typicode.com")

def body = """
{
    "title": "My Post",
    "body": "Post content",
    "userId": 1
}
"""

try {
    String response = client.postSync("/posts", body) {
        header("Content-Type", "application/json")
    }
    println response
} finally {
    client.close()
}
```

### Asynchronous Requests

```groovy
def client = new GroovyHttpClient("https://api.example.com")

// Async GET with CompletableFuture
client.get("/data").thenAccept { response ->
    println "Received: $response"
}.exceptionally { error ->
    println "Error: ${error.message}"
    return null
}

// Multiple parallel requests
def future1 = client.get("/users/1")
def future2 = client.get("/users/2")
def future3 = client.get("/users/3")

CompletableFuture.allOf(future1, future2, future3).thenRun {
    println "User 1: ${future1.get()}"
    println "User 2: ${future2.get()}"
    println "User 3: ${future3.get()}"
}
```

## Configuration

### Constructor Parameters

```groovy
new GroovyHttpClient(
    String host,                          // Base URL (required)
    Duration connectTimeout = 10s,        // Connection timeout
    Duration requestTimeout = 30s,        // Request timeout
    int failureThreshold = 5,             // Circuit breaker failure threshold
    long resetTimeoutMs = 30000,          // Circuit breaker reset time (ms)
    SSLContext sslContext = null,         // Custom SSL context
    HostnameVerifier hostnameVerifier = null  // Custom hostname verifier
)
```

### Default Values

- **Connect Timeout**: 10 seconds
- **Request Timeout**: 30 seconds
- **Sync Method Timeout**: 30 seconds
- **Circuit Breaker Failure Threshold**: 5 failures
- **Circuit Breaker Reset Timeout**: 30 seconds (30,000ms)

### Request Configuration with Closures

All HTTP methods accept an optional configuration closure for customizing requests:

```groovy
client.getSync("/api/data") {
    header("Authorization", "Bearer ${token}")
    header("Accept", "application/json")
    timeout(Duration.ofSeconds(20))
}

client.postSync("/api/users", jsonBody) {
    header("Content-Type", "application/json")
    header("X-Custom-Header", "value")
}
```

## Circuit Breaker Protection

The GroovyHttpClient includes built-in circuit breaker protection to prevent cascading failures when a service becomes unavailable.

### How It Works

1. **Closed State** (Normal): Requests flow through normally
2. **Failure Counting**: Each failed request (4xx/5xx status or exception) increments a counter
3. **Open State**: After reaching the failure threshold (default: 5), the circuit "opens"
4. **Rejection Period**: While open, all requests immediately fail with `CircuitOpenException`
5. **Half-Open/Reset**: After the reset timeout (default: 30s), the circuit attempts to close and retry

### Benefits

- **Fast Failure**: Prevents wasting resources on failing services
- **Service Protection**: Gives failing services time to recover
- **Automatic Recovery**: Circuit automatically attempts to close after timeout
- **Thread Safety**: Circuit breaker state is managed with atomic operations

### Configuration Example

```groovy
// Stricter circuit breaker: open after 3 failures, reset after 15 seconds
def client = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(10),  // connect timeout
    Duration.ofSeconds(30),  // request timeout
    3,                       // failure threshold
    15000                    // reset timeout (ms)
)
```

### Handling Circuit Breaker Exceptions

```groovy
try {
    String response = client.getSync("/api/data")
} catch (GroovyHttpClient.CircuitOpenException e) {
    println "Service temporarily unavailable: ${e.message}"
    // Handle gracefully - use cache, return default, etc.
} catch (GroovyHttpClient.HttpResponseException e) {
    println "HTTP error ${e.statusCode}: ${e.message}"
}
```

## Thread Safety

GroovyHttpClient is **fully thread-safe** and designed for concurrent use:

- **Virtual Threads**: Each HTTP request runs on a lightweight virtual thread
- **Shared Client**: Single client instance can be safely shared across multiple threads
- **Atomic Operations**: Circuit breaker uses atomic counters for thread-safe state management
- **Immutable Configuration**: Client configuration is set at construction and remains immutable

### Concurrent Usage Example

```groovy
def client = new GroovyHttpClient("https://api.example.com")

// Safe to use from multiple threads
def executor = Executors.newFixedThreadPool(10)
def futures = (1..100).collect { id ->
    executor.submit {
        client.getSync("/users/$id")
    }
}

futures.each { it.get() }
executor.shutdown()
client.close()
```

## Supported HTTP Methods

### Asynchronous Methods

All return `CompletableFuture<String>` (except HEAD/OPTIONS):

- `get(String path, Closure config = null)`
- `post(String path, String body, Closure config = null)`
- `put(String path, String body, Closure config = null)`
- `delete(String path, Closure config = null)`
- `patch(String path, String body, Closure config = null)`
- `head(String path, Closure config = null)` → returns `CompletableFuture<HttpResponse<Void>>`
- `options(String path, Closure config = null)` → returns `CompletableFuture<HttpResponse<Void>>`

### Synchronous Methods

All return `String` (except HEAD/OPTIONS) and accept optional timeout:

- `getSync(String path, Closure config = null, Duration timeout = 30s)`
- `postSync(String path, String body, Closure config = null, Duration timeout = 30s)`
- `putSync(String path, String body, Closure config = null, Duration timeout = 30s)`
- `deleteSync(String path, Closure config = null, Duration timeout = 30s)`
- `patchSync(String path, String body, Closure config = null, Duration timeout = 30s)`
- `headSync(String path, Closure config = null, Duration timeout = 30s)` → returns `HttpResponse<Void>`
- `optionsSync(String path, Closure config = null, Duration timeout = 30s)` → returns `HttpResponse<Void>`

## Use Case Examples

### Example 1: REST API Client

```groovy
import org.softwood.http.GroovyHttpClient
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class UserService {
    private final GroovyHttpClient client
    
    UserService() {
        client = new GroovyHttpClient("https://api.myservice.com")
    }
    
    def getUser(String userId) {
        String json = client.getSync("/users/$userId") {
            header("Authorization", "Bearer ${getToken()}")
            header("Accept", "application/json")
        }
        return new JsonSlurper().parseText(json)
    }
    
    def createUser(Map userData) {
        String body = JsonOutput.toJson(userData)
        String json = client.postSync("/users", body) {
            header("Authorization", "Bearer ${getToken()}")
            header("Content-Type", "application/json")
        }
        return new JsonSlurper().parseText(json)
    }
    
    def close() {
        client.close()
    }
}
```

### Example 2: Bulk Data Fetching with Parallel Requests

```groovy
import org.softwood.http.GroovyHttpClient
import java.util.concurrent.CompletableFuture

def client = new GroovyHttpClient("https://api.example.com")

// Fetch 100 users in parallel using virtual threads
def userIds = (1..100)
def futures = userIds.collect { id ->
    client.get("/users/$id")
}

// Wait for all requests to complete
CompletableFuture.allOf(futures as CompletableFuture[]).join()

// Process results
def users = futures.collect { it.get() }
println "Fetched ${users.size()} users"

client.close()
```

### Example 3: Resilient API Client with Error Handling

```groovy
import org.softwood.http.GroovyHttpClient
import static org.softwood.http.GroovyHttpClient.*

def client = new GroovyHttpClient(
    "https://api.example.com",
    Duration.ofSeconds(5),
    Duration.ofSeconds(15),
    3,      // Open circuit after 3 failures
    10000   // Try again after 10 seconds
)

def fetchWithRetry(String path, int maxRetries = 3) {
    int attempt = 0
    while (attempt < maxRetries) {
        try {
            return client.getSync(path)
        } catch (CircuitOpenException e) {
            println "Circuit open, waiting..."
            Thread.sleep(5000)
            attempt++
        } catch (HttpResponseException e) {
            println "HTTP error ${e.statusCode}, attempt ${attempt + 1}"
            attempt++
            if (attempt >= maxRetries) throw e
        }
    }
}

try {
    def data = fetchWithRetry("/important/data")
    println data
} finally {
    client.close()
}
```

## Best Practices

1. **Reuse Client Instances**: Create one client per service/host and reuse it
2. **Always Close**: Use try-finally or try-with-resources to ensure `close()` is called
3. **Configure Timeouts**: Set appropriate timeouts based on your service's characteristics
4. **Handle Exceptions**: Always handle `CircuitOpenException` and `HttpResponseException`
5. **Use Async for High Throughput**: Leverage async methods for parallel operations
6. **Monitor Circuit State**: Log circuit breaker events to understand service health

## Resource Management

```groovy
// Automatic cleanup with try-with-resources
new GroovyHttpClient("https://api.example.com").withCloseable { client ->
    String response = client.getSync("/data")
    // client.close() called automatically
}
```

## Requirements

- Java 21 or later (for virtual thread support)
- Groovy 4.x or later

## License

Check your project's license file for details.