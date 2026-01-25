package org.softwood.http.testingFrameworkTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.HttpClientTestTrait
import org.softwood.http.SecurityConfig
import spock.lang.Specification

/**
 * Example showing how to use HttpClientTestTrait for even more concise tests
 */
class HttpClientTestTraitSpec extends Specification implements HttpClientTestTrait {

    def cleanupSpec() {
        stopTestServer()
    }

    def cleanup() {
        resetTestServer()
    }

    def "trait provides convenient access to test DSL"() {
        given: "Mock endpoint using trait method"
        mockEndpoint("/api/test") {
            status 200
            json([message: "Hello from trait"])
        }

        when: "Making request with trait-created client"
        def client = createTestClient()
        def response = client.getSync("/api/test")

        then:
        response.json.message == "Hello from trait"

        and: "Verify using trait method"
        verifyRequest("/api/test")
                .wasCalled()
                .withMethod("GET")
    }

    def "trait allows testing user service classes"() {
        given: "A user service"
        def service = new UserApiClient(testServerUrl)

        and: "Mock endpoints"
        mockEndpoint("/users/list") {
            status 200
            json([
                    users: [
                            [id: 1, name: "Alice"],
                            [id: 2, name: "Bob"]
                    ]
            ])
        }

        when:
        def users = service.listUsers()

        then:
        users.size() == 2
        users[0].name == "Alice"

        and:
        verifyRequest("/users/list")
                .wasCalled()
                .withHeader("Accept", "application/json")
    }

    def "trait supports multiple endpoints"() {
        given:
        mockEndpoint("/api/endpoint1").respondWithStatus(200)
        mockEndpoint("/api/endpoint2").respondWithStatus(200)

        when:
        def client = createTestClient()
        client.getSync("/api/endpoint1")
        client.getSync("/api/endpoint2")

        then:
        verifyRequest("/api/endpoint1").wasCalled()
        verifyRequest("/api/endpoint2").wasCalled()
    }

    def "trait can verify request details"() {
        given:
        mockEndpoint("/api/data").respondWithStatus(200)

        when:
        def client = createTestClient()
        client.postSync("/api/data", null) {
            header("Content-Type", "application/json")
            json([name: "test", value: 42])
        }

        then:
        verifyRequest("/api/data")
                .wasCalled()
                .withMethod("POST")
                .withHeader("Content-Type", "application/json")
                .withJsonBody { json ->
                    json.name == "test" && json.value == 42
                }
    }

    def "trait can access request history"() {
        given:
        mockEndpoint("/api/test").respondWithStatus(200)

        when:
        def client = createTestClient()
        client.getSync("/api/test") { header("X-Request", "first") }
        client.getSync("/api/test") { header("X-Request", "second") }

        then:
        def requests = getRecordedRequests("/api/test")
        requests.size() == 2
        requests[0].getHeader("X-Request") == "first"
        requests[1].getHeader("X-Request") == "second"
    }

    def "trait supports query parameter verification"() {
        given:
        mockEndpoint("/api/search").respondWithJson([results: []])

        when:
        def client = createTestClient()
        client.getSync("/api/search") {
            query(q: "groovy", page: 1, limit: 10)
        }

        then:
        verifyRequest("/api/search")
                .wasCalled()
                .withQueryParam("q", "groovy")
                .withQueryParam("page", "1")
                .withQueryParam("limit", "10")
    }

    // ========== Helper: Example user service class ==========

    static class UserApiClient {
        private final GroovyHttpClient client

        UserApiClient(String baseUrl) {
            this.client = new GroovyHttpClient(SecurityConfig.testing(baseUrl))
        }

        List<Map> listUsers() {
            def response = client.getSync("/users/list") {
                header("Accept", "application/json")
            }
            response.json.users
        }
    }
}