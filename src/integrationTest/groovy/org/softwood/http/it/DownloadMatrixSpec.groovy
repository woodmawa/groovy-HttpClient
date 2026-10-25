package org.softwood.http.it

import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.features.Feature
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/** 2.1 streamed downloads against the real server, on every protocol. */
class DownloadMatrixSpec extends IntegrationSpec {

    @TempDir
    Path dir

    boolean noPartFiles() {
        Files.walk(dir).withCloseable { s -> s.noneMatch { it.fileName.toString().endsWith('.part') } }
    }

    @Feature('stream.download-path')
    def "a large body downloads to a file over #protocol"() {
        given:
        Path target = dir.resolve("big-${protocol}.bin")

        when:
        def r = client(protocol).downloadSync('/bytes?n=2000000', target)

        then:
        r.statusCode == 200
        r.bytesRead == 2_000_000L
        Files.size(target) == 2_000_000L
        Files.readAllBytes(target).every { it == (byte) 120 }
        noPartFiles()

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.download-path')
    def "an error status raises and writes no file over #protocol"() {
        given:
        Path target = dir.resolve("err-${protocol}.bin")

        when:
        client(protocol) { errorBodyPreviewChars(0) }.downloadSync('/status/404', target) {
            query(type: 'application/json', body: '{"why":"absent"}')
        }

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 404
        e.json == [why: 'absent']
        !Files.exists(target)
        noPartFiles()

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.download-path')
    def "maxResponseBytes stops a download with ResponseTooLargeException over #protocol"() {
        given:
        Path target = dir.resolve("limit-${protocol}.bin")

        when:
        client(protocol).downloadSync('/bytes?n=2000000', target) { maxResponseBytes 100_000 }

        then:
        def e = thrown(ResponseTooLargeException)
        e.limit == 100_000L
        !Files.exists(target)
        noPartFiles()

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.download-path')
    def "a compressed body is decoded into the file over #protocol"() {
        given:
        Path target = dir.resolve("gz-${protocol}.txt")

        when:
        def r = client(protocol) { compression(true) }.downloadSync('/gzip', target)

        then:
        r.decoded
        Files.readString(target) == FeatureServer.COMPRESSIBLE

        where:
        protocol << Protocol.available()
    }

    @Feature('stream.download-path')
    def "a redirected download streams only the final body over #protocol"() {
        given:
        def out = new ByteArrayOutputStream()

        when:
        def r = client(protocol).downloadSync('/redirect/2', out)

        then:
        r.statusCode == 200
        new groovy.json.JsonSlurper().parseText(out.toString('UTF-8')).path == '/echo'

        where:
        protocol << Protocol.available()
    }
}
