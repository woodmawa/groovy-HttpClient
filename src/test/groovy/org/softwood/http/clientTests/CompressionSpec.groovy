package org.softwood.http.clientTests

import groovy.json.JsonOutput
import org.softwood.http.ContentDecodingException
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.MultipartPart
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 2.1 compression: opt-in response decoding, the zip-bomb guard, and gzip request bodies. */
class CompressionSpec extends Specification {

    static final String TEXT = ('The quick brown fox jumps over the lazy dog. ' * 200)

    MockHttpServer server = new MockHttpServer()

    def setup() { server.init() }

    def cleanup() { server.shutdown() }

    String base() { "http://localhost:${server.port}" }

    GroovyHttpClient client(Map overrides = [:]) {
        new GroovyHttpClient(SecurityConfig.testing(base()).withOverrides(overrides))
    }

    static GroovyHttpClient.RetryPolicy retries(int attempts) {
        new GroovyHttpClient.RetryPolicy.Config().tap {
            maxAttempts(attempts); onIoExceptions(true); on5xx(true); backoffExponential(1, 1, false)
        }.build()
    }

    static byte[] gzip(byte[] b) {
        def bos = new ByteArrayOutputStream()
        new GZIPOutputStream(bos).withCloseable { it.write(b) }
        bos.toByteArray()
    }

    static byte[] gzip(String s) { gzip(s.getBytes(StandardCharsets.UTF_8)) }

    static byte[] deflate(String s, boolean raw) {
        def bos = new ByteArrayOutputStream()
        new DeflaterOutputStream(bos, new Deflater(Deflater.DEFAULT_COMPRESSION, raw)).withCloseable {
            it.write(s.getBytes(StandardCharsets.UTF_8))
        }
        bos.toByteArray()
    }

    static byte[] concat(byte[] a, byte[] b) {
        def bos = new ByteArrayOutputStream()
        bos.write(a); bos.write(b)
        bos.toByteArray()
    }

    static byte[] corrupt(byte[] b) {
        byte[] c = b.clone()
        for (int i = 20; i < 40; i++) c[i] = (byte) 0xFF
        c
    }

    static String gunzip(byte[] b) { new GZIPInputStream(new ByteArrayInputStream(b)).getText('UTF-8') }

    // ================================================================ compression.gzip-response

    @Feature('compression.gzip-response')
    def "off by default: no Accept-Encoding is sent and an encoded body arrives as received"() {
        given:
        def check = server.addRequestCheck('GET', '/z', 200).withResponseBytes(gzip(TEXT))
                .withResponseHeaders(['Content-Encoding': 'gzip', 'Content-Type': 'text/plain'])

        when:
        def r = client().getSync('/z')

        then:
        check.lastRequest.header('Accept-Encoding') == null
        r.bytes == gzip(TEXT)
        r.getHeader('Content-Encoding') == 'gzip'
        r.contentEncoding == 'gzip'
        !r.decoded
    }

    @Feature('compression.gzip-response')
    def "compression on: '#encoding' is advertised, decoded, and its headers dropped"() {
        given:
        def check = server.addRequestCheck('GET', '/z', 200).withResponseBytes(body)
                .withResponseHeaders(['Content-Encoding': encoding, 'Content-Type': 'text/plain; charset=UTF-8'])

        when:
        def r = client(compression: true).getSync('/z')

        then:
        check.lastRequest.header('Accept-Encoding') == 'gzip, deflate'
        r.body == expected
        r.decoded
        r.contentEncoding == encoding
        r.getHeader('Content-Encoding') == null
        r.getHeader('Content-Length') == null
        r.contentType.startsWith('text/plain')

        where:
        encoding  | body                               | expected
        'gzip'    | gzip(TEXT)                         | TEXT
        'x-gzip'  | gzip(TEXT)                         | TEXT
        'GZIP'    | gzip(TEXT)                         | TEXT
        'gzip'    | concat(gzip('first '), gzip('second')) | 'first second'
        'deflate' | deflate(TEXT, false)               | TEXT
        'deflate' | deflate(TEXT, true)                | TEXT
    }

    @Feature('compression.gzip-response')
    def "an unadvertised encoding is passed through untouched"() {
        given:
        server.addRequestCheck('GET', '/br', 200).withResponseBytes([1, 2, 3] as byte[])
                .withResponseHeaders(['Content-Encoding': 'br'])

        when:
        def r = client(compression: true).getSync('/br')

        then:
        r.bytes == [1, 2, 3] as byte[]
        !r.decoded
        r.getHeader('Content-Encoding') == 'br'
    }

    @Feature('compression.gzip-response')
    def "a caller's own Accept-Encoding wins and nothing is decoded"() {
        given:
        def check = server.addRequestCheck('GET', '/z', 200).withResponseBytes(gzip(TEXT))
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        when:
        def r = client(compression: true).getSync('/z') { header 'Accept-Encoding', 'gzip' }

        then:
        check.lastRequest.header('Accept-Encoding') == 'gzip'
        r.bytes == gzip(TEXT)
        !r.decoded
    }

    @Feature('compression.gzip-response')
    def "per-request compression overrides the client setting (#clientOn -> #requestOn)"() {
        given:
        def check = server.addRequestCheck('GET', '/z', 200).withResponseBytes(gzip(TEXT))
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        when:
        def r = client(compression: clientOn).getSync('/z') { compression requestOn }

        then:
        (check.lastRequest.header('Accept-Encoding') != null) == requestOn
        r.decoded == requestOn

        where:
        clientOn | requestOn
        true     | false
        false    | true
    }

    @Feature('compression.gzip-response')
    def "the builder enables compression"() {
        given:
        server.addRequestCheck('GET', '/z', 200).withResponseBytes(gzip(TEXT))
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        expect:
        GroovyHttpClient.builder(base()).compression(true).build().getSync('/z').body == TEXT
    }

    @Feature('compression.gzip-response')
    def "error bodies are decoded before they reach the exception"() {
        given:
        server.addRequestCheck('GET', '/missing', 404).withResponseBytes(gzip('{"code":"NOPE"}'))
                .withResponseHeaders(['Content-Encoding': 'gzip', 'Content-Type': 'application/json'])

        when:
        client(compression: true, errorBodyPreviewChars: 0).getSync('/missing')

        then:
        def e = thrown(HttpResponseException)
        e.json == [code: 'NOPE']
        e.responseBody == '{"code":"NOPE"}'
    }

    @Feature('compression.gzip-response')
    def "an empty body labelled gzip is an empty body, not an error"() {
        given:
        server.addRequestCheck('GET', '/empty', 200).withResponseBytes(new byte[0])
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        expect:
        client(compression: true).getSync('/empty').bytes.length == 0
    }

    @Feature('compression.gzip-response')
    def "a #what gzip body fails with ContentDecodingException, which is not retried"() {
        given:
        def check = server.addRequestCheck('GET', '/bad', 200).withResponseBytes(body)
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        when:
        client(compression: true, retryPolicy: retries(3)).getSync('/bad')

        then:
        thrown(ContentDecodingException)
        check.received.size() == 1

        where:
        what        | body
        'truncated' | Arrays.copyOf(gzip(TEXT), gzip(TEXT).length - 20)
        'corrupt'   | corrupt(gzip(TEXT))
        'non-gzip'  | 'plain text, not gzip'.bytes
    }

    // ================================================================ compression.zip-bomb-guard

    @Feature('compression.zip-bomb-guard')
    def "maxResponseBytes counts decompressed bytes and stops inflating at the limit"() {
        given: 'a small gzip body that inflates to 64 MB'
        byte[] bomb = gzip(new byte[64 * 1024 * 1024])
        assert bomb.length < 200_000
        server.addRequestCheck('GET', '/bomb', 200).withResponseBytes(bomb)
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        when:
        client(compression: true, maxResponseBytes: 1_000_000L).getSync('/bomb')

        then:
        def e = thrown(ResponseTooLargeException)
        e.limit == 1_000_000L
        e.bytesRead > 1_000_000L
        e.bytesRead < 1_000_000L + 65_536    // stopped within one inflate buffer, never inflated in full
    }

    @Feature('compression.zip-bomb-guard')
    def "a decoded body exactly at the limit passes"() {
        given:
        server.addRequestCheck('GET', '/z', 200).withResponseBytes(gzip(TEXT))
                .withResponseHeaders(['Content-Encoding': 'gzip'])

        expect:
        client(compression: true, maxResponseBytes: TEXT.length() as long).getSync('/z').body == TEXT
    }

    // ================================================================ compression.gzip-request

    @Feature('compression.gzip-request')
    def "gzipBody() compresses a JSON body and labels it"() {
        given:
        def check = server.addRequestCheck('POST', '/in', 200)

        when:
        client().postSync('/in', null) { json([text: TEXT]); gzipBody() }

        then:
        def req = check.lastRequest
        req.header('Content-Encoding') == 'gzip'
        req.header('Content-Type').startsWith('application/json')
        gunzip(req.body) == JsonOutput.toJson([text: TEXT])
        req.body.length < TEXT.length()
    }

    @Feature('compression.gzip-request')
    def "gzipBody() works for codec bodies and multipart, and is never automatic"() {
        given:
        def check = server.addRequestCheck('POST', '/in', 200)
        def c = client(compression: true)

        when:
        c.postSync('/in', null) { body([a: 1], 'application/json') }

        then: 'response compression does not compress requests'
        check.lastRequest.header('Content-Encoding') == null

        when:
        c.postSync('/in', null) { body([a: 1], 'application/json'); gzipBody() }

        then:
        gunzip(check.lastRequest.body) == '{"a":1}'

        when:
        c.postMultipartSync('/in', [MultipartPart.text('field', 'value')]) { gzipBody() }

        then:
        check.lastRequest.header('Content-Encoding') == 'gzip'
        gunzip(check.lastRequest.body).contains('name="field"')
    }

    @Feature('compression.gzip-request')
    def "a gzipped body is re-sent intact on retry"() {
        given:
        def check = server.addRequestCheck('PUT', '/in', 503)

        when:
        client(retryPolicy: retries(2)).putSync('/in', null) { json([a: TEXT]); gzipBody() }

        then:
        thrown(HttpResponseException)
        check.received.size() == 2
        check.received.every { gunzip(it.body) == JsonOutput.toJson([a: TEXT]) }
    }
}
