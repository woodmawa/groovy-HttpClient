package org.softwood.http.it

import org.softwood.http.features.Feature
import spock.lang.TempDir

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.SubmissionPublisher

/** 2.1 streaming uploads against the real server, on every protocol; /sink answers length and SHA-256. */
class UploadMatrixSpec extends IntegrationSpec {

    @TempDir
    Path dir

    static byte[] payload(int n) {
        byte[] b = new byte[n]
        new Random(7).nextBytes(b)
        b
    }

    static String sha256(byte[] b) { MessageDigest.getInstance('SHA-256').digest(b).encodeHex().toString() }

    @Feature('stream.upload')
    def "a Path body streams with its length over #protocol"() {
        given:
        byte[] content = payload(4_000_000)
        Path p = dir.resolve("up-${protocol.name()}.bin")
        Files.write(p, content)

        when:
        def sink = client(protocol).putSync('/sink', null) { body p, 'application/octet-stream' }.json

        then:
        sink.method == 'PUT'
        sink.length == 4_000_000
        sink.sha256 == sha256(content)

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.upload')
    def "an InputStream body streams over #protocol"() {
        given:
        byte[] content = payload(1_500_000)

        when:
        def sink = client(protocol).postSync('/sink', null) { body new ByteArrayInputStream(content), 'application/octet-stream' }.json

        then:
        sink.length == 1_500_000
        sink.sha256 == sha256(content)

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.upload')
    def "a Flow.Publisher body streams over #protocol"() {
        given:
        def publisher = new SubmissionPublisher<ByteBuffer>()
        def parts = (1..50).collect { "part ${it};".toString() }

        when:
        def future = client(protocol).post('/sink', null) { body publisher, 'text/plain' }
        Thread.start {
            while (publisher.numberOfSubscribers == 0) Thread.sleep(5)   // a hot publisher drops items until subscribed
            parts.each { publisher.submit(ByteBuffer.wrap(it.bytes)) }
            publisher.close()
        }
        def sink = future.get().json

        then:
        sink.sha256 == sha256(parts.join('').bytes)

        where:
        protocol << Protocol.available()
    }
}
