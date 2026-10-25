package org.softwood.http.clientTests

import groovy.json.JsonOutput
import org.softwood.http.AuthenticationException
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/** 2.1 OAuth2 client credentials: token caching, single flight, refresh on 401, and secret hygiene. */
class OAuth2Spec extends Specification {

    static final String SECRET = 'client-s3cret/with+chars'

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0,
                retryPolicy: GroovyHttpClient.RetryPolicy.none()] + overrides))
    }

    def tokenEndpoint(Map body = [access_token: 'tok-1', token_type: 'Bearer', expires_in: 3600], int status = 200) {
        server.addRequestCheck('POST', '/token', status).withResponseBody(JsonOutput.toJson(body))
                .withResponseHeaders(['Content-Type': 'application/json'])
    }

    Closure oauth(String mode = 'basic') {
        String url = "${base()}/token"
        return {
            oauth2 {
                tokenUrl url
                clientId 'my-client'
                clientSecret SECRET
                scope 'orders.read', 'orders.write'
                clientAuth mode
            }
        }
    }

    static Map<String, String> form(byte[] body) {
        new String(body, StandardCharsets.UTF_8).split('&').collectEntries { String p ->
            def kv = p.split('=', 2)
            [(URLDecoder.decode(kv[0], 'UTF-8')): URLDecoder.decode(kv.size() > 1 ? kv[1] : '', 'UTF-8')]
        }
    }

    @Feature('auth.oauth2-client-credentials')
    def "the token is fetched with client_secret_basic and sent as a bearer token"() {
        given:
        def token = tokenEndpoint()
        def api = server.addRequestCheck('GET', '/orders', 200)

        when:
        client().auth(oauth()).getSync('/orders')

        then:
        api.lastRequest.header('Authorization') == 'Bearer tok-1'
        def t = token.lastRequest
        t.header('Content-Type') == 'application/x-www-form-urlencoded'
        t.header('Authorization') == 'Basic ' + Base64.encoder.encodeToString(
                "my-client:${URLEncoder.encode(SECRET, 'UTF-8')}".toString().getBytes(StandardCharsets.UTF_8))
        form(t.body) == [grant_type: 'client_credentials', scope: 'orders.read orders.write']
    }

    @Feature('auth.oauth2-client-credentials')
    def "client_secret_post puts the client credentials in the form instead"() {
        given:
        def token = tokenEndpoint()
        server.addRequestCheck('GET', '/orders', 200)

        when:
        client().auth(oauth('post')).getSync('/orders')

        then:
        token.lastRequest.header('Authorization') == null
        form(token.lastRequest.body).client_id == 'my-client'
        form(token.lastRequest.body).client_secret == SECRET
    }

    @Feature('auth.oauth2-client-credentials')
    def "the token is cached until expires_in minus the skew"() {
        given:
        def token = tokenEndpoint(body)
        server.addRequestCheck('GET', '/orders', 200)
        def c = client().auth(oauth())

        when:
        3.times { c.getSync('/orders') }

        then:
        token.received.size() == fetches

        where:
        body                                                            | fetches
        [access_token: 't', token_type: 'Bearer', expires_in: 3600]     | 1
        [access_token: 't', token_type: 'bearer', expires_in: '3600']   | 1
        [access_token: 't']                                             | 1
        [access_token: 't', token_type: 'Bearer', expires_in: 30]       | 3
    }

    @Feature('auth.oauth2-client-credentials')
    def "concurrent first requests share one token fetch"() {
        given:
        def token = tokenEndpoint().withDelay(200)
        server.addRequestCheck('GET', '/orders', 200)
        def c = client().auth(oauth())

        when:
        List<CompletableFuture> calls = (1..20).collect { c.get('/orders') }
        CompletableFuture.allOf(calls as CompletableFuture[]).get()

        then:
        token.received.size() == 1
        calls.every { it.get().statusCode == 200 }
    }

    @Feature('auth.oauth2-client-credentials')
    def "a 401 refreshes the token and re-sends once"() {
        given:
        def token = tokenEndpoint()
        def api = server.addRequestCheck('GET', '/orders', 401)

        when:
        client().auth(oauth()).getSync('/orders')

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 401
        token.received.size() == 2
        api.received.size() == 2
    }

    @Feature('auth.oauth2-client-credentials')
    def "a one-shot body is not re-sent after a 401"() {
        given:
        def token = tokenEndpoint()
        def api = server.addRequestCheck('POST', '/orders', 401)

        when:
        client().auth(oauth()).postSync('/orders', null) { body new ByteArrayInputStream('x'.bytes), 'text/plain' }

        then:
        thrown(HttpResponseException)
        api.received.size() == 1
        token.received.size() == 1
    }

    @Feature('auth.oauth2-client-credentials')
    def "a token endpoint error raises AuthenticationException with the error code and nothing secret"() {
        given:
        tokenEndpoint([error: 'invalid_client', error_description: "secret ${SECRET} rejected".toString()], 401)
        def api = server.addRequestCheck('GET', '/orders', 200)

        when:
        client().auth(oauth()).getSync('/orders')

        then:
        def e = thrown(AuthenticationException)
        e.statusCode == 401
        e.error == 'invalid_client'
        e.message.contains('invalid_client')
        !e.message.contains(SECRET)
        !e.message.contains('rejected')
        api.received.isEmpty()
    }

    @Feature('auth.oauth2-client-credentials')
    def "a token response without access_token or with a non-bearer type is refused"() {
        given:
        tokenEndpoint(body)
        server.addRequestCheck('GET', '/orders', 200)

        when:
        client().auth(oauth()).getSync('/orders')

        then:
        thrown(AuthenticationException)

        where:
        body << [[token_type: 'Bearer'], [access_token: 't', token_type: 'mac']]
    }

    @Feature('auth.oauth2-client-credentials')
    def "a plain-HTTP token endpoint is refused unless loopback or insecureAllowed"() {
        given:
        server.addRequestCheck('GET', '/orders', 200)

        when:
        client().auth {
            oauth2 { tokenUrl 'http://login.example.com/token'; clientId 'c'; clientSecret 's' }
        }.getSync('/orders')

        then:
        def e = thrown(AuthenticationException)
        e.message.contains('must be HTTPS')
    }

    @Feature('auth.oauth2-client-credentials')
    def "the token endpoint is exempt from allowedHosts, but only for its own URL"() {
        given: 'allowedHosts names only localhost; the token endpoint is reached as 127.0.0.1'
        def token = tokenEndpoint()
        def api = server.addRequestCheck('GET', '/orders', 200)
        String url = "http://127.0.0.1:${server.port}/token"

        when:
        client(allowedHosts: ['localhost'] as Set).auth {
            oauth2 { tokenUrl url; clientId 'c'; clientSecret 's' }
        }.getSync('/orders')

        then:
        token.received.size() == 1
        api.lastRequest.header('Authorization') == 'Bearer tok-1'
    }

    @Feature('auth.oauth2-client-credentials')
    def "toString never shows the client secret"() {
        expect:
        !org.softwood.http.auth.AuthPolicy.from(oauth()).toString().contains(SECRET)
    }
}
