package org.softwood.http.it

import org.softwood.http.GroovyHttpClient.ResponseTooLargeException
import org.softwood.http.features.Feature

/** 2.1 response { } handlers against the real server, on every protocol. */
class ResponseHandlersMatrixSpec extends IntegrationSpec {

    @Feature('response.handlers')
    def "status handlers turn #status into a value over #protocol"() {
        when:
        def v = client(protocol).getSync(Object, "/status/${status}") {
            response {
                success { r -> 'ok' }
                when(404) { r -> 'missing' }
                when(500..599) { r -> "server:${r.statusCode}".toString() }
                failure { r -> "other:${r.statusCode}".toString() }
            }
        }

        then:
        v == expected

        where:
        [protocol, status] << [Protocol.available(), [200, 404, 503, 418]].combinations()
        expected = [200: 'ok', 404: 'missing', 503: 'server:503', 418: 'other:418'][status]
    }

    @Feature('response.handlers')
    def "a typed verb maps the body when success passes the response through over #protocol"() {
        when:
        Map json = client(protocol).getSync(Map, '/json') { response { when(404) { null } } }

        then:
        json.greeting == 'hello'

        where:
        protocol << Protocol.available()
    }

    @Feature('response.handlers')
    def "exception { } receives the size limit over #protocol"() {
        when:
        def v = client(protocol) { maxResponseBytes(1000) }.getSync(Object, '/bytes?n=200000') {
            response { exception { Throwable t -> t.getClass() } }
        }

        then:
        v == ResponseTooLargeException

        where:
        protocol << Protocol.available()
    }
}
