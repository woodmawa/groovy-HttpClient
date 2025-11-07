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

### Creating a Client with a builder
there is also two static methods for creating clients with default timeouts and other configuration details
To start configuring your GroovyHttpClient, first create an instance of the static inner Builder class:

def httpClientBuilder = GroovyHttpClient.Builder(), then set the options before the final build()
```groovy
// Create a new HTTP client instance
def httpClient = GroovyHttpClient.Builder()
        .setBaseUrl("https://api.example.com")
        .setConnectionTimeout(Duration.ofSeconds(10))
        .setRequestTimeout(Duration.ofMinutes(1))
        .setCircuitBreakerFailureThreshold(5)
        .setCircuitBreakerResetTimeout(10000) // 10 seconds
        .build()


```

If you don't set any options, the following defaults will be used:

Base URL: http://localhost
Connection Timeout: 10 seconds
Request Timeout: 1 minute
Circuit Breaker Failure Threshold: 5
Circuit Breaker Reset Timeout: 10000 milliseconds (10 seconds)

### Creating GroovyHttpClient with Custom Options

The `GroovyHttpClient` class provides a convenient way to create instances with custom options using the `createWithOptions` static factory method. This allows you to set specific configuration properties directly through a Map parameter.

#### Usage Syntax

```groovy
def httpClient = GroovyHttpClient.createWithOptions(baseUrl, options)
baseUrl: The base URL for all requests made by this client.
        options: A map of additional configuration options.
The options map supports the following keys (all values should be Long type):

Key	Description
connectionTimeout	Connection timeout in seconds
requestTimeout	Request timeout in seconds
circuitBreakerFailureThreshold	Number of failures to open circuit breaker
circuitBreakerResetTimeout	Circuit reset timeout in milliseconds
Example Usage
def options = [
        connectionTimeout: Duration.ofSeconds(10),
        requestTimeout: Duration.ofMinutes(2),
        circuitBreakerFailureThreshold: 7,
        circuitBreakerResetTimeout: 15000 // 15 seconds
]

def httpClient = GroovyHttpClient.createWithOptions("https://api.example.com", options)
Default Values

```
If any option is not provided in the map, default values will be used:

| Option                         | Default Value                |
|--------------------------------|------------------------------|
| `connectionTimeout`            | 10 seconds                   |
| `requestTimeout`               | 1 minute                     |
| `circuitBreakerFailureThreshold` | 5                           |
| `circuitBreakerResetTimeout`     | 10000 milliseconds (10 seconds) |

How It Works
The method initializes a new GroovyHttpClient instance with the provided base URL. Then, it iterates through the supported option keys and sets each value if present in the options map:

### Creates a new GroovyHttpClient instance using an options map 
Iterates through the predefined property names (connectionTimeout, requestTimeout, etc.)
For each property:
Retrieves its value from the options map (defaulting to null if not found)
If a non-null value is found, sets it on the client using reflection
This approach provides flexibility in creating configured clients while keeping the constructor simple.

> [!NOTE]
> Note on Type Safety
>While this method accepts Long values for all parameters, you can pass Duration objects or integers that will be implicitly converted. This makes usage more convenient when working with time-related configurations:

#### Usage Syntax
```groovy
def httpClient = GroovyHttpClient.createWithOptions(
        "https://api.example.com",
        [connectionTimeout: 10, requestTimeout: 120] // values are in seconds
)
```

#### Advanced Usage
You can also use this method to create clients with only specific options set:

```groovy
// Only setting the circuit breaker properties
def httpClient = GroovyHttpClient.createWithOptions(
        "https://api.example.com",
        [circuitBreakerFailureThreshold: 8, circuitBreakerResetTimeout: 20000]
)
```

This method provides a convenient alternative to using the builder pattern when you only need to set specific options without chaining multiple builder methods.

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