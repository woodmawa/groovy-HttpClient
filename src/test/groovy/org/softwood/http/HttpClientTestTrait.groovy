package org.softwood.http

import spock.lang.Shared

/**
 * Trait that can be mixed into Spock specifications to provide
 * convenient access to HttpClientTestDsl functionality.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class MyServiceSpec extends Specification implements HttpClientTestTrait {
 *
 *     def "should test something"() {
 *         given:
 *         mockEndpoint("/api/test") {
 *             status 200
 *             json([data: "value"])
 *         }
 *
 *         when:
 *         def result = myService.doSomething()
 *
 *         then:
 *         result != null
 *         verifyRequest("/api/test").wasCalled()
 *     }
 * }
 * }</pre>
 */
trait HttpClientTestTrait {

    @Shared
    private HttpClientTestDsl _testServer

    /**
     * Get or create the test server instance
     */
    HttpClientTestDsl getTestServer() {
        if (_testServer == null) {
            _testServer = HttpClientTestDsl.create()
            _testServer.start()
        }
        _testServer
    }

    /**
     * Get the base URL of the test server
     */
    String getTestServerUrl() {
        testServer.baseUrl
    }

    /**
     * Create a client configured for the test server
     */
    GroovyHttpClient createTestClient(Closure config = null) {
        testServer.createClient(config)
    }

    /**
     * Shorthand for defining a mock endpoint
     */
    HttpClientTestDsl.MockEndpoint mockEndpoint(String path) {
        testServer.when(path)
    }

    /**
     * Shorthand for defining a mock endpoint with response config
     */
    HttpClientTestDsl.MockEndpoint mockEndpoint(
            String path,
            @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = HttpClientTestDsl.ResponseBuilder) Closure config
    ) {
        testServer.when(path).respondWith(config)
    }

    /**
     * Shorthand for verifying requests
     */
    HttpClientTestDsl.RequestVerification verifyRequest(String path) {
        testServer.verify(path)
    }

    /**
     * Shorthand for resetting test server
     */
    void resetTestServer() {
        testServer.reset()
    }

    /**
     * Shorthand for getting recorded requests
     */
    List<HttpClientTestDsl.RecordedRequest> getRecordedRequests(String path = null) {
        path ? testServer.getRequestsFor(path) : testServer.recordedRequests
    }

    /**
     * Cleanup method - call this in cleanupSpec() if needed
     */
    void stopTestServer() {
        _testServer?.stop()
        _testServer = null
    }
}