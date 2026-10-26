package org.softwood.http

import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

/** 2.1: text and JSON parts are plain form fields (RFC 7578: no filename); file parts keep their filename. */
@Feature('request.multipart')
class MultipartFieldsSpec extends Specification {

    MockHttpServer server = new MockHttpServer()
    GroovyHttpClient client

    def setup() {
        server.init()
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
    }

    def cleanup() { client?.close(); server.shutdown() }

    private static String disposition(String body, String name) {
        body.readLines().find { it.startsWith("Content-Disposition: form-data; name=\"${name}\"") }
    }

    def "text and json parts are sent as fields, file parts with their filename"() {
        given:
        def check = server.addRequestCheck('POST', '/upload', 200)

        when:
        client.postMultipartSync('/upload', [
                MultipartPart.text('description', 'stock take'),
                MultipartPart.json('metadata', '{"q":"Q3"}'),
                MultipartPart.bytes('file', 'stock.csv', 'text/csv', 'sku\nA1\n'.bytes)
        ])
        String body = check.lastRequest.bodyText

        then:
        disposition(body, 'description') == 'Content-Disposition: form-data; name="description"'
        disposition(body, 'metadata') == 'Content-Disposition: form-data; name="metadata"'
        disposition(body, 'file') == 'Content-Disposition: form-data; name="file"; filename="stock.csv"'
        body.contains('stock take')
    }

    def "in the DSL, content is a field unless a filename is given, and data is an upload"() {
        given:
        def check = server.addRequestCheck('POST', '/upload', 200)

        when:
        client.postMultipartSync('/upload') { b ->
            b.part { name 'note'; content 'hello' }
            b.part { name 'readme'; filename 'readme.txt'; content 'Hello World' }
            b.part { name 'blob'; data([1, 2, 3] as byte[]) }
        }
        String body = check.lastRequest.bodyText

        then:
        disposition(body, 'note') == 'Content-Disposition: form-data; name="note"'
        disposition(body, 'readme') == 'Content-Disposition: form-data; name="readme"; filename="readme.txt"'
        disposition(body, 'blob') == 'Content-Disposition: form-data; name="blob"; filename="blob.bin"'
    }

    def "a factory part has no filename"() {
        expect:
        MultipartPart.text('a', 'x').filename == null
        MultipartPart.json('b', '{}').filename == null
        MultipartPart.bytes('c', 'c.pdf', 'application/pdf', new byte[1]).filename == 'c.pdf'
    }
}
