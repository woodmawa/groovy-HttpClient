package org.softwood.http.clientTests

import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpClientResponse
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.SecurityConfig
import org.softwood.http.StreamResult
import org.softwood.http.features.Feature
import org.softwood.http.streaming.StreamTarget
import org.softwood.test.MockHttpServer
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/** 2.1 streamed downloads: to a Path via temp file + atomic move, or to a caller's OutputStream. */
class DownloadSpec extends Specification {

    @TempDir
    Path dir

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides([errorBodyPreviewChars: 0] + overrides))
    }

    static byte[] payload(int n) {
        byte[] b = new byte[n]
        for (int i = 0; i < n; i++) b[i] = (byte) (97 + (i % 26))
        b
    }

    List<String> leftovers() {
        Files.walk(dir).withCloseable { s -> s.map { it.fileName.toString() }.filter { it.endsWith('.part') }.toList() }
    }

    @Feature('stream.download-path')
    def "a download to a Path writes the body and reports what it wrote"() {
        given:
        byte[] body = payload(300_000)
        server.addRequestCheck('GET', '/f', 200).withResponseBytes(body)
                .withResponseHeaders(['Content-Type': 'application/octet-stream'])
        Path target = dir.resolve('out/f.bin')

        when:
        StreamResult r = client().downloadSync('/f', target)

        then:
        Files.readAllBytes(target) == body
        r.statusCode == 200
        r.bytesRead == 300_000L
        r.path == target.toAbsolutePath()
        r.contentType == 'application/octet-stream'
        r.items == 0
        !r.stopped
        leftovers().isEmpty()
        Files.list(dir.resolve('out')).withCloseable { it.count() } == 1
    }

    @Feature('stream.download-path')
    def "the async form completes with the StreamResult"() {
        given:
        server.addRequestCheck('GET', '/f', 200).withResponseBytes(payload(10))
        Path target = dir.resolve('a.bin')

        expect:
        client().download('/f', target).get().bytesRead == 10L
        Files.size(target) == 10L
    }

    @Feature('stream.download-path')
    def "an error status raises with the body in memory; an existing target is left untouched"() {
        given:
        server.addRequestCheck('GET', '/missing', 404).withResponseBody('{"error":"gone"}')
                .withResponseHeaders(['Content-Type': 'application/json'])
        Path target = dir.resolve('keep.txt')
        Files.writeString(target, 'old contents')

        when:
        client().downloadSync('/missing', target)

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 404
        e.json == [error: 'gone']
        Files.readString(target) == 'old contents'
        leftovers().isEmpty()
    }

    @Feature('stream.download-path')
    def "an existing target is replaced only by a complete download"() {
        given:
        server.addRequestCheck('GET', '/f', 200).withResponseBytes(payload(1000))
        Path target = dir.resolve('replace.bin')
        Files.writeString(target, 'old')

        when:
        client().downloadSync('/f', target)

        then:
        Files.readAllBytes(target) == payload(1000)
    }

    @Feature('stream.download-path')
    def "maxResponseBytes counts bytes written; over it the temp file goes and the target never appears"() {
        given:
        server.addRequestCheck('GET', '/big', 200).withResponseBytes(payload(200_000))
        Path target = dir.resolve('big.bin')

        when:
        client().downloadSync('/big', target) { maxResponseBytes 50_000 }

        then:
        def e = thrown(ResponseTooLargeException)
        e.limit == 50_000L
        e.bytesRead > 50_000L
        !Files.exists(target)
        leftovers().isEmpty()
    }

    @Feature('stream.download-path')
    def "a per-request maxResponseBytes raises the client's limit for that request only"() {
        given:
        server.addRequestCheck('GET', '/big', 200).withResponseBytes(payload(200_000))
        def c = client(maxResponseBytes: 1000L)

        when:
        def r = c.downloadSync('/big', dir.resolve('big.bin')) { maxResponseBytes 1_000_000 }

        then:
        r.bytesRead == 200_000L

        when:
        c.getSync('/big')

        then:
        thrown(ResponseTooLargeException)
    }

    @Feature('stream.download-path')
    def "a per-request maxResponseBytes must be positive"() {
        when:
        client().getSync('/x') { maxResponseBytes 0 }

        then:
        thrown(IllegalArgumentException)
    }

    @Feature('stream.download-path')
    def "a download to an OutputStream writes into it and leaves it open"() {
        given:
        server.addRequestCheck('GET', '/f', 200).withResponseBytes(payload(70_000))
        def out = new TrackingStream()

        when:
        def r = client().downloadSync('/f', out)

        then:
        out.toByteArray() == payload(70_000)
        !out.closed
        r.bytesRead == 70_000L
        r.path == null
    }

    static class TrackingStream extends ByteArrayOutputStream {
        boolean closed = false
        @Override void close() { closed = true; super.close() }
    }

    @Feature('stream.download-path')
    def "a File target works like a Path"() {
        given:
        server.addRequestCheck('GET', '/f', 200).withResponseBytes(payload(5))
        File f = dir.resolve('file.bin').toFile()

        expect:
        client().downloadSync('/f', f).bytesRead == 5L
        f.bytes == payload(5)
    }

    @Feature('stream.download-path')
    def "with compression on, the file holds the decoded body"() {
        given:
        def bos = new ByteArrayOutputStream()
        new GZIPOutputStream(bos).withCloseable { it.write(payload(100_000)) }
        server.addRequestCheck('GET', '/z', 200).withResponseBytes(bos.toByteArray())
                .withResponseHeaders(['Content-Encoding': 'gzip'])
        Path target = dir.resolve('z.bin')

        when:
        def r = client(compression: true).downloadSync('/z', target)

        then:
        Files.readAllBytes(target) == payload(100_000)
        r.decoded
        r.contentEncoding == 'gzip'
        r.bytesRead == 100_000L
    }

    @Feature('stream.download-path')
    def "downloadTo in a request closure works with any verb; the typed verb returns the StreamResult"() {
        given:
        def check = server.addRequestCheck('POST', '/export', 200).withResponseBytes(payload(64))
        Path target = dir.resolve('export.bin')

        when:
        StreamResult r = client().postSync(StreamResult, '/export', '{"q":1}') { downloadTo target }

        then:
        check.lastRequest.bodyText == '{"q":1}'
        r.bytesRead == 64L
        Files.readAllBytes(target) == payload(64)

        when: 'the untyped verb keeps its 2.0 return type, with an empty body'
        HttpClientResponse resp = client().postSync('/export', '{"q":1}') { downloadTo dir.resolve('again.bin') }

        then:
        resp.statusCode == 200
        resp.bytes.length == 0
        Files.size(dir.resolve('again.bin')) == 64L
    }

    @Feature('stream.download-path')
    def "response handlers apply: a 404 handled as null leaves no file"() {
        given:
        server.addRequestCheck('GET', '/missing', 404)
        Path target = dir.resolve('none.bin')

        when:
        def r = client().getSync(Object, '/missing') {
            downloadTo target
            response { when(404) { null } }
        }

        then:
        r == null
        !Files.exists(target)
        leftovers().isEmpty()
    }

    @Feature('stream.download-path')
    def "the retry rule: a Path download may retry, an OutputStream one only until its first byte"() {
        given:
        def path = new StreamTarget.PathTarget(dir.resolve('r.bin'))
        def stream = new StreamTarget.OutputStreamTarget(new ByteArrayOutputStream())

        when:
        path.openAttempt().write([1] as byte[], 0, 1)
        def s = stream.openAttempt()

        then:
        path.retryable
        stream.retryable

        when:
        s.write([1] as byte[], 0, 1)

        then:
        !stream.retryable

        cleanup:
        path.abort()
    }

    @Feature('stream.download-path')
    def "a status retry before any body byte still downloads to the OutputStream"() {
        given: 'a policy that retries 503; the server keeps answering 503'
        def check = server.addRequestCheck('GET', '/busy', 503)
        def out = new ByteArrayOutputStream()
        def retry = new GroovyHttpClient.RetryPolicy.Config().tap { maxAttempts(3); on5xx(true); backoffExponential(1, 1, false) }.build()

        when:
        client(retryPolicy: retry).downloadSync('/busy', out)

        then: 'error bodies never reach the target, so every attempt was allowed'
        thrown(HttpResponseException)
        check.received.size() == 3
        out.size() == 0
    }
}
