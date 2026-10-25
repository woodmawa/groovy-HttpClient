package org.softwood.http.it

import org.softwood.http.GroovyHttpClient.HttpResponseException
import org.softwood.http.features.Feature

/** 2.1 auth helpers against the real server, on every protocol. */
class AuthMatrixSpec extends IntegrationSpec {

    @Feature('auth.basic')
    def "basic auth is accepted, and wrong credentials are refused, over #protocol"() {
        when:
        def ok = client(protocol) { auth { basic FeatureServer.BASIC_USER, FeatureServer.BASIC_PASS } }.getSync('/auth/basic')

        then:
        ok.body == 'authorised'

        when:
        client(protocol) { auth { basic FeatureServer.BASIC_USER, 'wrong' } }.getSync('/auth/basic')

        then:
        def e = thrown(HttpResponseException)
        e.statusCode == 401

        where:
        protocol << Protocol.available()
    }

    @Feature('auth.bearer')
    def "bearer auth is accepted over #protocol"() {
        expect:
        client(protocol) { auth { bearer FeatureServer.BEARER_TOKEN } }.getSync('/auth/bearer').body == 'authorised'

        where:
        protocol << Protocol.available()
    }
}
