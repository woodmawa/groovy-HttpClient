package org.softwood.http.clientTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.AutoCleanup
import spock.lang.Specification

/**
 * Found by the 2.1 real-server matrix: GroovyHttpClient.get(String) is the HTTP GET verb, and Groovy also uses a
 * get(String) method as a generic property getter. Before 2.1 a name the request closure could not resolve on
 * its delegate fell through to the client and became GET /name, its CompletableFuture used as the value.
 */
class DslNameResolutionSpec extends Specification {

    static final String CALLER_CONSTANT = 'value-from-the-caller'

    String callerField = 'field-from-the-caller'

    MockHttpServer server = new MockHttpServer()
    @AutoCleanup GroovyHttpClient client

    def setup() {
        server.init()
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
    }

    def cleanup() { server.shutdown() }

    @Feature('request.dsl-validation')
    def "a caller's constants, fields and locals inside a request closure resolve to the caller, not to a GET"() {
        given:
        def target = server.addRequestCheck('GET', '/q', 200)
        def stray1 = server.addRequestCheck('GET', '/CALLER_CONSTANT', 200)
        def stray2 = server.addRequestCheck('GET', '/callerField', 200)
        def local = 'local-value'

        when:
        client.getSync('/q') { query(a: CALLER_CONSTANT, b: callerField, c: local) }

        then:
        target.lastRequest.query == [a: ['value-from-the-caller'], b: ['field-from-the-caller'], c: ['local-value']]
        stray1.received.isEmpty()
        stray2.received.isEmpty()
    }

    @Feature('request.dsl-validation')
    def "an unknown property on the client fails and sends nothing"() {
        given:
        def stray = server.addRequestCheck('GET', '/nonsense', 200)

        when:
        client.nonsense

        then:
        def e = thrown(MissingPropertyException)
        e.property == 'nonsense'
        stray.received.isEmpty()
    }

    @Feature('request.dsl-validation')
    def "real client properties still read normally"() {
        expect:
        client.config.baseUrl.startsWith('http://localhost:')
        client.httpVersion != null
        client.codecs != null
        client.interceptors.isEmpty()
        client.cookieHandler != null
        client.defaultHeaders == [:]
    }
}
