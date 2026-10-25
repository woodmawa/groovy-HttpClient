package org.softwood.http.testingFrameworkTests

import org.softwood.http.HttpClientTestDsl
import org.softwood.http.HttpClientTestTrait
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

/**
 * 2.1 published test fixtures: the three helpers are built from src/testFixtures (the java-test-fixtures variant),
 * not from the test source set. The consumerSmoke task proves a separate build can resolve and use them.
 */
class PublishedFixturesSpec extends Specification {

    @Feature('testing.published-fixtures')
    def "#type.simpleName is compiled from the testFixtures source set"() {
        expect:
        String where = type.protectionDomain.codeSource.location.toString().replace('\\', '/')
        where.contains('/testFixtures/') || where.endsWith('-test-fixtures.jar')

        where:
        type << [MockHttpServer, HttpClientTestDsl, HttpClientTestTrait]
    }
}
