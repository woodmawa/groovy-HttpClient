import org.softwood.http.GroovyHttpClient
import org.softwood.http.HttpClientTestDsl
import org.softwood.http.HttpClientTestTrait
import org.softwood.test.MockHttpServer
import spock.lang.Specification

/** Runs in a separate build against the published artifacts only (see the root consumerSmoke task). */
class ConsumerSmokeSpec extends Specification {

    def "the published library and its test fixtures resolve and work together"() {
        given:
        def server = new MockHttpServer()
        server.init()
        server.addRequestCheck('GET', '/ping', 200).withResponseBody('pong')

        expect:
        GroovyHttpClient.builder("http://localhost:${server.port}").build().getSync('/ping').body == 'pong'

        cleanup:
        server.shutdown()
    }

    def "every fixture class is on the consumer's test classpath"() {
        expect:
        [MockHttpServer, HttpClientTestDsl, HttpClientTestTrait].every { it != null }
    }

    def "Maven consumers find the fixtures under the test-fixtures classifier"() {
        given:
        String v = System.getProperty('libVersion')
        File dir = new File(System.getProperty('testRepo'), "io/github/woodmawa/groovy-httpclient/${v}")

        expect:
        new File(dir, "groovy-httpclient-${v}.jar").isFile()
        new File(dir, "groovy-httpclient-${v}-test-fixtures.jar").isFile()
        new File(dir, "groovy-httpclient-${v}.pom").isFile()
        new File(dir, "groovy-httpclient-${v}.module").text.contains('testFixtures')
    }
}
