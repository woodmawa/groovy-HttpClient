package org.softwood.http.it

import groovy.json.JsonOutput
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.features.Feature

import java.util.zip.GZIPInputStream

/** 2.1 compression against the real server, on every protocol. */
class CompressionMatrixSpec extends IntegrationSpec {

    @Feature('compression.gzip-response')
    def "#path is advertised, decoded and its encoding headers dropped over #protocol"() {
        given:
        def c = client(protocol) { compression(true) }

        when:
        def r = c.getSync(path)

        then:
        r.body == FeatureServer.COMPRESSIBLE
        r.decoded
        r.contentEncoding == path.substring(1)
        r.getHeader('Content-Encoding') == null
        r.getHeader('Content-Length') == null

        where:
        [protocol, path] << [Protocol.available(), ['/gzip', '/deflate']].combinations()
    }

    @Feature('compression.gzip-response')
    def "compression off sends no Accept-Encoding, so the server answers uncompressed over #protocol"() {
        when:
        def r = client(protocol).getSync('/gzip')

        then:
        r.body == FeatureServer.COMPRESSIBLE
        !r.decoded
        r.contentEncoding == null

        where:
        protocol << Protocol.available()
    }

    @Feature('compression.zip-bomb-guard')
    def "maxResponseBytes stops a decoded body at the limit over #protocol"() {
        given:
        def c = client(protocol) { compression(true); maxResponseBytes(1_000_000L) }

        when:
        c.getSync('/gzip?n=50000000')

        then:
        def e = thrown(ResponseTooLargeException)
        e.limit == 1_000_000L
        e.bytesRead > 1_000_000L
        e.bytesRead < 1_100_000L

        where:
        protocol << Protocol.available()
    }

    @Feature('compression.gzip-request')
    def "gzipBody() sends a gzip body the server can inflate over #protocol"() {
        given:
        def payload = [text: FeatureServer.COMPRESSIBLE]

        when:
        def r = client(protocol).postSync('/echo', null) { json(payload); gzipBody() }
        def echo = r.json
        byte[] sent = Base64.decoder.decode(echo.bodyBase64 as String)

        then:
        echo.headers['content-encoding'] == ['gzip']
        (echo.headers['content-type'][0] as String).startsWith('application/json')
        new GZIPInputStream(new ByteArrayInputStream(sent)).getText('UTF-8') == JsonOutput.toJson(payload)
        sent.length < FeatureServer.COMPRESSIBLE.length()

        where:
        protocol << Protocol.available()
    }
}
