package org.softwood.http.clientTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.SecurityConfig
import org.softwood.http.auth.AuthPolicy
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/** 2.1 auth helpers: basic, bearer, API key; where credentials go and where they never go. */
class AuthSpec extends Specification {

    static final String SECRET = 'S3cr3t-p\u00e4ss'
    static final String API_KEY = 'k3y-0123456789abcdef'

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0] + overrides))
    }

    static String basicHeader(String user, String pass) {
        'Basic ' + Base64.encoder.encodeToString("${user}:${pass}".toString().getBytes(StandardCharsets.UTF_8))
    }

    @Feature('auth.basic')
    def "basic sends RFC 7617 credentials, UTF-8 encoded"() {
        given:
        def check = server.addRequestCheck('GET', '/a', 200)

        when:
        client().auth { basic 'svc-user', SECRET }.getSync('/a')

        then:
        check.lastRequest.header('Authorization') == basicHeader('svc-user', SECRET)
    }

    @Feature('auth.basic')
    def "a user id with a colon is refused"() {
        when:
        client().auth { basic 'a:b', 'x' }

        then:
        thrown(IllegalArgumentException)
    }

    @Feature('auth.basic')
    def "the builder sets client-wide auth"() {
        given:
        def check = server.addRequestCheck('GET', '/a', 200)

        when:
        GroovyHttpClient.builder(base()).auth { basic 'u', 'p' }.build().getSync('/a')

        then:
        check.lastRequest.header('Authorization') == basicHeader('u', 'p')
    }

    @Feature('auth.bearer')
    def "bearer sends a static token, or asks its supplier on every attempt"() {
        given:
        def check = server.addRequestCheck('GET', '/b', 503)
        int calls = 0
        def retry = new GroovyHttpClient.RetryPolicy.Config().tap { maxAttempts(3); on5xx(true); backoffExponential(1, 1, false) }.build()

        when:
        client(retryPolicy: retry).auth { bearer { -> "tok-${++calls}" } }.getSync('/b')

        then:
        thrown(HttpResponseException)
        check.received*.header('Authorization') == ['Bearer tok-1', 'Bearer tok-2', 'Bearer tok-3']

        when:
        def ok = server.addRequestCheck('GET', '/ok', 200)
        client().auth { bearer 'static-token' }.getSync('/ok')

        then:
        ok.lastRequest.header('Authorization') == 'Bearer static-token'
    }

    @Feature('auth.bearer')
    def "a failing token supplier fails the request with its exception"() {
        when:
        client().auth { bearer { -> throw new IllegalStateException('vault down') } }.getSync('/x')

        then:
        def e = thrown(IllegalStateException)
        e.message == 'vault down'
    }

    @Feature('auth.api-key')
    def "apiKey goes in a header or a query parameter"() {
        given:
        def check = server.addRequestCheck('GET', '/k', 200)

        when:
        client().auth { apiKey header: 'X-API-Key', value: API_KEY }.getSync('/k')

        then:
        check.lastRequest.header('X-API-Key') == API_KEY

        when:
        client().auth { apiKey query: 'api_key', value: 'a&b=c' }.getSync('/k?x=1')

        then:
        check.lastRequest.rawQuery == 'x=1&api_key=a%26b%3Dc'
    }

    @Feature('auth.api-key')
    def "apiKey needs exactly one of header: or query:"() {
        when:
        client().auth { apiKey value: 'k' }

        then:
        thrown(IllegalArgumentException)
    }

    @Feature('auth.basic')
    def "the caller's own Authorization header wins; noAuth() and per-request auth override the client"() {
        given:
        def check = server.addRequestCheck('GET', '/a', 200)
        def c = client().auth { bearer 'client-token' }

        when:
        c.getSync('/a') { header 'Authorization', 'Custom mine' }

        then:
        check.lastRequest.header('Authorization') == 'Custom mine'

        when:
        c.getSync('/a') { noAuth() }

        then:
        check.lastRequest.header('Authorization') == null

        when:
        c.getSync('/a') { auth { bearer 'request-token' } }

        then:
        check.lastRequest.header('Authorization') == 'Bearer request-token'
    }

    @Feature('auth.basic')
    def "credentials go to the base origin only, unless authHosts names another host"() {
        given:
        def check = server.addRequestCheck('GET', '/a', 200)
        def c = client(allowAbsoluteUrls: true, allowedHosts: ['localhost', '127.0.0.1'] as Set)
        String other = "http://127.0.0.1:${server.port}/a"

        when:
        c.auth { bearer 't' }.getSync(other)

        then:
        check.lastRequest.header('Authorization') == null

        when:
        c.auth { bearer 't'; authHosts '127.0.0.1' }.getSync(other)

        then:
        check.lastRequest.header('Authorization') == 'Bearer t'
    }

    @Feature('auth.api-key')
    def "a cross-origin redirect strips Authorization and a custom API-key header"() {
        given:
        server.addRequestCheck('GET', '/from', 302).withResponseHeaders(['Location': "http://127.0.0.1:${server.port}/to".toString()])
        def to = server.addRequestCheck('GET', '/to', 200)
        def c = client(allowedHosts: ['localhost', '127.0.0.1'] as Set)

        when:
        c.auth { apiKey header: 'X-API-Key', value: API_KEY }.getSync('/from')

        then:
        to.received.size() == 1
        to.lastRequest.header('X-API-Key') == null

        when:
        c.auth { bearer 'tok' }.getSync('/from')

        then:
        to.lastRequest.header('Authorization') == null
    }

    @Feature('auth.bearer')
    def "secrets never appear in toString or in exception messages"() {
        given:
        server.addRequestCheck('GET', '/denied', 401).withResponseBody('no')

        expect:
        !AuthPolicy.from { basic 'svc', SECRET }.toString().contains(SECRET)
        !AuthPolicy.from { bearer SECRET }.toString().contains(SECRET)
        !AuthPolicy.from { apiKey header: 'X-API-Key', value: SECRET }.toString().contains(SECRET)

        when:
        client().auth { bearer SECRET }.getSync('/denied')

        then:
        def e = thrown(HttpResponseException)
        !e.message.contains(SECRET)
        !e.toString().contains(SECRET)
    }

    @Feature('auth.basic')
    def "auth { } takes exactly one scheme"() {
        when:
        client().auth { basic 'u', 'p'; bearer 't' }

        then:
        thrown(IllegalStateException)

        when:
        client().auth { authHosts 'x' }

        then:
        thrown(IllegalArgumentException)
    }
}
