package org.softwood.http.it

import org.softwood.http.AuthenticationException
import org.softwood.http.features.Feature

/** 2.1 OAuth2 client credentials against the real server (HTTPS token endpoint), on every protocol. */
class OAuth2MatrixSpec extends IntegrationSpec {

    @Feature('auth.oauth2-client-credentials')
    def "a client-credentials token unlocks a bearer endpoint over #protocol"() {
        given:
        String url = server.baseUrl(protocol) + '/oauth/token'

        expect:
        client(protocol) {
            auth { oauth2 { tokenUrl url; clientId 'it-client'; clientSecret 'it-secret' } }
        }.getSync('/auth/bearer').body == 'authorised'

        where:
        protocol << Protocol.available()
    }

    @Feature('auth.oauth2-client-credentials')
    def "a rejected client raises AuthenticationException over #protocol"() {
        given:
        String url = server.baseUrl(protocol) + '/oauth/token'

        when:
        client(protocol) {
            auth { oauth2 { tokenUrl url; clientId 'it-client'; clientSecret 'wrong' } }
        }.getSync('/auth/bearer')

        then:
        def e = thrown(AuthenticationException)
        e.error == 'invalid_client'

        where:
        protocol << Protocol.available()
    }
}
