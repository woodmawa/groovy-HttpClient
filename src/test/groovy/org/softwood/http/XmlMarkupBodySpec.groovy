package org.softwood.http

import org.softwood.test.MockHttpServer
import org.softwood.http.features.Feature
import spock.lang.Specification

/** 2.1: an XML markup closure written inside a request closure must reach the markup builder, not the request DSL. */
@Feature('codec.xml-markup')
class XmlMarkupBodySpec extends Specification {

    MockHttpServer server = new MockHttpServer()
    GroovyHttpClient client

    def setup() {
        server.init()
        client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))
    }

    def cleanup() { client?.close(); server.shutdown() }

    def "a markup closure inline in the request closure builds the document"() {
        given:
        def check = server.addRequestCheck('POST', '/xml', 200)
        String sku = 'A1'

        when:
        client.postSync('/xml', null) {
            header 'X-Test', 'markup'
            body({ order(id: 7) { line(sku: sku, qty: 2) } }, 'application/xml')
        }

        then:
        check.lastRequest.bodyText.contains("<order id='7'><line sku='A1' qty='2'/></order>")
        check.lastRequest.header('Content-Type').startsWith('application/xml')
        check.lastRequest.header('X-Test') == 'markup'
    }

    def "a markup closure defined outside still works"() {
        given:
        def check = server.addRequestCheck('POST', '/xml', 200)
        def doc = { invoice(no: 'I-1') { total('12.50') } }

        when:
        client.postSync('/xml', null) { body(doc, 'application/xml') }

        then:
        check.lastRequest.bodyText.contains("<invoice no='I-1'><total>12.50</total></invoice>")
    }
}
