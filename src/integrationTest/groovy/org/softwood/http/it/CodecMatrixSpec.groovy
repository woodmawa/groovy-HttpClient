package org.softwood.http.it

import groovy.json.JsonSlurper
import org.softwood.http.features.Feature

/** 2.1 codec registry against the real server, on every protocol. */
class CodecMatrixSpec extends IntegrationSpec {

    @Feature('codec.registry')
    def "a registered encoder and parser carry a custom media type over #protocol"() {
        given:
        def c = client(protocol) {
            codecs {
                encoder 'text/x-shout', { body -> body.toString().toUpperCase() }
                parser 'application/json', { String s -> [wrapped: new JsonSlurper().parseText(s)] }
            }
        }

        when:
        def r = c.postSync('/echo', null) { body('hello codecs', 'text/x-shout; charset=UTF-8') }

        then:
        r.parsed.wrapped.body == 'HELLO CODECS'
        r.parsed.wrapped.headers['content-type'][0] == 'text/x-shout; charset=UTF-8'

        where:
        protocol << Protocol.available()
    }

    @Feature('codec.structured-suffix')
    def "application/problem+json and vnd.tmf+json parse as JSON over #protocol"() {
        when:
        def r = client(protocol).getSync('/status/200') { query(type: type, body: '{"title":"t","n":[1,2]}') }

        then:
        r.contentType.startsWith(type)
        r.parsed == [title: 't', n: [1, 2]]

        where:
        [protocol, type] << [Protocol.available(), ['application/problem+json', 'application/vnd.tmf+json; version=4']].combinations()
    }
}
