package org.softwood.http.clientTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.RedirectRejectedException
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.SubmissionPublisher
import java.util.zip.GZIPInputStream

/** 2.1 streaming uploads: Path (replayable), InputStream and Flow.Publisher (one-shot). */
class UploadSpec extends Specification {

    @TempDir
    Path dir

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0] + overrides))
    }

    static GroovyHttpClient.RetryPolicy retries(int attempts) {
        new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(attempts); onIoExceptions(true); on5xx(true); backoffExponential(1, 1, false)
        }.build()
    }

    static byte[] payload(int n) {
        byte[] b = new byte[n]
        new Random(42).nextBytes(b)
        b
    }

    Path file(String name, byte[] content) {
        Path p = dir.resolve(name)
        Files.write(p, content)
        p
    }

    @Feature('stream.upload')
    def "a Path body is streamed with its length and the given or default content type"() {
        given:
        def check = server.addRequestCheck('PUT', '/f', 200)
        byte[] content = payload(3_000_000)
        Path p = file('data.bin', content)

        when:
        client().putSync('/f', null) { body p, 'application/x-custom' }

        then:
        check.lastRequest.body == content
        check.lastRequest.header('Content-Length') == '3000000'
        check.lastRequest.header('Content-Type') == 'application/x-custom'

        when:
        client().putSync('/f', null) { body p }

        then:
        check.lastRequest.header('Content-Type') != null
    }

    @Feature('stream.upload')
    def "a File body works like a Path"() {
        given:
        def check = server.addRequestCheck('POST', '/f', 200)
        File f = file('f.txt', 'hello file'.bytes).toFile()

        when:
        client().postSync('/f', null) { body f, 'text/plain' }

        then:
        check.lastRequest.bodyText == 'hello file'
    }

    @Feature('stream.upload')
    def "a Path body is replayable: it is re-sent on retry"() {
        given:
        def check = server.addRequestCheck('PUT', '/busy', 503)
        byte[] content = payload(10_000)
        Path p = file('r.bin', content)

        when:
        client(retryPolicy: retries(3)).putSync('/busy', null) { body p }

        then:
        thrown(HttpResponseException)
        check.received.size() == 3
        check.received.every { it.body == content }
    }

    @Feature('stream.upload')
    def "an InputStream body is streamed once and never retried, whatever the policy"() {
        given:
        def ok = server.addRequestCheck('PUT', '/ok', 200)
        def busy = server.addRequestCheck('PUT', '/busy', 503)
        byte[] content = payload(500_000)

        when:
        client().putSync('/ok', null) { body new ByteArrayInputStream(content), 'application/octet-stream' }

        then:
        ok.lastRequest.body == content
        ok.lastRequest.header('Content-Type') == 'application/octet-stream'

        when:
        client(retryPolicy: retries(3)).putSync('/busy', null) { body new ByteArrayInputStream(content), 'application/octet-stream' }

        then:
        thrown(HttpResponseException)
        busy.received.size() == 1
    }

    @Feature('stream.upload')
    def "a Flow.Publisher body is streamed"() {
        given:
        def check = server.addRequestCheck('POST', '/pub', 200)
        def publisher = new SubmissionPublisher<ByteBuffer>()

        when:
        def future = client().post('/pub', null) { body publisher, 'text/plain' }
        Thread.start {
            while (publisher.numberOfSubscribers == 0) Thread.sleep(5)   // a hot publisher drops items until subscribed
            ['alpha ', 'beta ', 'gamma'].each { publisher.submit(ByteBuffer.wrap(it.bytes)) }
            publisher.close()
        }
        future.get()

        then:
        check.lastRequest.bodyText == 'alpha beta gamma'
    }

    @Feature('stream.upload')
    def "a #code redirect re-sends a Path body but refuses a one-shot body"() {
        given:
        server.addRequestCheck('POST', '/a', code).withResponseHeaders(['Location': '/b'])
        def b = server.addRequestCheck('POST', '/b', 200)
        Path p = file('redirect.bin', 'replayable'.bytes)

        when:
        client().postSync('/a', null) { body p, 'text/plain' }

        then:
        b.lastRequest.bodyText == 'replayable'

        when:
        client().postSync('/a', null) { body new ByteArrayInputStream('once'.bytes), 'text/plain' }

        then:
        def e = thrown(RedirectRejectedException)
        e.message.contains('cannot be re-sent')

        where:
        code << [307, 308]
    }

    @Feature('stream.upload')
    def "a 303 redirect drops the body, so a one-shot body may follow it"() {
        given:
        server.addRequestCheck('POST', '/a', 303).withResponseHeaders(['Location': '/b'])
        def b = server.addRequestCheck('GET', '/b', 200).withResponseBody('done')

        expect:
        client().postSync('/a', null) { body new ByteArrayInputStream('once'.bytes), 'text/plain' }.body == 'done'
        b.received.size() == 1
    }

    @Feature('stream.upload')
    def "gzipBody() compresses a streamed Path body"() {
        given:
        def check = server.addRequestCheck('PUT', '/z', 200)
        byte[] content = ('compressible ' * 100_000).bytes
        Path p = file('big.txt', content)

        when:
        client().putSync('/z', null) { body p, 'text/plain'; gzipBody() }

        then:
        check.lastRequest.header('Content-Encoding') == 'gzip'
        new GZIPInputStream(new ByteArrayInputStream(check.lastRequest.body)).bytes == content
        check.lastRequest.body.length < content.length.intdiv(10)
    }

    @Feature('stream.upload')
    def "a missing upload file fails before anything is sent"() {
        given:
        def check = server.addRequestCheck('PUT', '/f', 200)

        when:
        client().putSync('/f', null) { body dir.resolve('absent.bin') }

        then:
        thrown(FileNotFoundException)
        check.received.isEmpty()
    }
}
