package org.softwood.http.testingFrameworkTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.HttpClientTestDsl
import org.softwood.http.SecurityConfig
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.AutoCleanup

/**
 * Specification demonstrating the HttpClientTestDsl framework.
 *
 * This shows users how to:
 * - Set up mock HTTP endpoints
 * - Configure responses with the DSL
 * - Test their own code that uses GroovyHttpClient
 * - Verify request expectations
 */
class HttpClientTestDslSpec extends Specification {

    @Shared
    @AutoCleanup
    HttpClientTestDsl testDsl

    @Shared
    GroovyHttpClient client

    def setupSpec() {
        testDsl = HttpClientTestDsl.create()
        testDsl.start()
        client = testDsl.createClient()
    }

    def cleanup() {
        testDsl.reset()
    }

    // ========== Basic Response Mocking ==========

    def "should mock a simple GET endpoint"() {
        given: "A mock endpoint returning JSON"
        testDsl.when("/api/users")
                .respondWith {
                    status 200
                    json([users: ["Alice", "Bob", "Charlie"]])
                }

        when: "Making a GET request"
        def response = client.getSync("/api/users")

        then: "Response contains expected data"
        response.statusCode == 200
        response.json.users.size() == 3
        response.json.users.contains("Alice")
    }

    def "should mock different status codes"() {
        given: "An endpoint returning 404"
        testDsl.when("/api/missing")
                .respondWithStatus(404)

        when: "Making a request"
        client.getSync("/api/missing")

        then: "Should throw exception for 404"
        def ex = thrown(Exception)
        ex.message.contains("404")
    }

    def "should mock custom headers"() {
        given: "An endpoint with custom headers"
        testDsl.when("/api/data")
                .respondWith {
                    status 200
                    header("X-Custom-Header", "test-value")
                    header("X-Rate-Limit", "100")
                    text("OK")
                }

        when: "Making a request"
        def response = client.getSync("/api/data")

        then: "Headers are present"
        response.getHeader("X-Custom-Header") == "test-value"
        response.getHeader("X-Rate-Limit") == "100"
    }

    // ========== Request Verification ==========

    def "should verify that endpoint was called"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)

        when:
        client.getSync("/api/test")

        then:
        testDsl.verify("/api/test").wasCalled()
    }

    def "should verify call count"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)

        when:
        3.times { client.getSync("/api/test") }

        then:
        testDsl.verify("/api/test").wasCalled(3)
    }

    def "should verify endpoint was not called"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)
        testDsl.when("/api/other").respondWithStatus(200)

        when:
        client.getSync("/api/test")

        then:
        testDsl.verify("/api/other").wasNotCalled()
    }

    def "should verify request headers"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)

        when:
        client.getSync("/api/test") {
            header("X-Custom", "my-value")
            header("Authorization", "Bearer token123")
        }

        then:
        testDsl.verify("/api/test")
                .wasCalled()
                .withHeader("X-Custom", "my-value")
                .withHeader("Authorization", "Bearer token123")
    }

    def "should verify query parameters"() {
        given:
        testDsl.when("/api/search").respondWithStatus(200)

        when:
        client.getSync("/api/search") {
            query(q: "groovy", limit: 10)
        }

        then:
        testDsl.verify("/api/search")
                .wasCalled()
                .withQueryParam("q", "groovy")
                .withQueryParam("limit", "10")
    }

    def "should verify request body"() {
        given:
        testDsl.when("/api/create").respondWithStatus(201)

        when:
        client.postSync("/api/create", "test body")

        then:
        testDsl.verify("/api/create")
                .wasCalled()
                .withMethod("POST")
                .withBodyContaining("test")
    }

    def "should verify JSON request body"() {
        given:
        testDsl.when("/api/users").respondWithStatus(201)

        when:
        client.postSync("/api/users", null) {
            json([name: "Alice", age: 30])
        }

        then:
        testDsl.verify("/api/users")
                .wasCalled()
                .withJsonBody { json ->
                    json.name == "Alice" && json.age == 30
                }
    }

    // ========== Multiple Requests & History ==========

    def "should track request history"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)

        when:
        client.getSync("/api/test") {
            header("X-Request", "first")
        }
        client.getSync("/api/test") {
            header("X-Request", "second")
        }

        then:
        def requests = testDsl.getRequestsFor("/api/test")
        requests.size() == 2
        requests[0].getHeader("X-Request") == "first"
        requests[1].getHeader("X-Request") == "second"
    }

    def "should access first and last requests"() {
        given:
        testDsl.when("/api/test").respondWithStatus(200)

        when:
        3.times { client.getSync("/api/test") }

        then:
        def verification = testDsl.verify("/api/test")
        verification.first() != null
        verification.last() != null
    }

    // ========== Testing User Code ==========

    def "should test user's service class that uses GroovyHttpClient"() {
        given: "A user's service class"
        def userService = new UserService(testDsl.baseUrl)

        and: "Mock API endpoint"
        testDsl.when("/users/123")
                .respondWithJson([id: 123, name: "Alice", email: "alice@example.com"])

        when: "User calls their service method"
        def user = userService.getUser(123)

        then: "Service returns expected data"
        user.name == "Alice"
        user.email == "alice@example.com"

        and: "Request was made with correct headers"
        testDsl.verify("/users/123")
                .wasCalled()
                .withHeader("Accept", "application/json")
    }

    def "should test user's async operations"() {
        given:
        def userService = new UserService(testDsl.baseUrl)

        and:
        testDsl.when("/users/search")
                .respondWithJson([users: [
                        [id: 1, name: "Alice"],
                        [id: 2, name: "Bob"]
                ]])

        when:
        def future = userService.searchUsersAsync("test")
        def users = future.get()

        then:
        users.size() == 2
        testDsl.verify("/users/search")
                .wasCalled()
                .withQueryParam("q", "test")
    }

    // ========== Helper: Example user service class ==========

    static class UserService {
        final GroovyHttpClient client

        UserService(String baseUrl) {
            this.client = new GroovyHttpClient(SecurityConfig.testing(baseUrl))
        }

        Map getUser(int id) {
            def response = client.getSync("/users/${id}") {
                header("Accept", "application/json")
            }
            response.json
        }

        def searchUsersAsync(String searchQuery) {
            client.get("/users/search") {
                query(q: searchQuery)
            }.thenApply { response -> response.json.users }
        }
    }
}