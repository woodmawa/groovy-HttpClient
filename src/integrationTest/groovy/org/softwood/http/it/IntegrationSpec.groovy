package org.softwood.http.it

import org.softwood.http.GroovyHttpClient
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Base for real-server specs. Name the data variable {@code protocol} and draw it from
 * {@code Protocol.available()} so the feature report can place each iteration in the matrix:
 *
 * <pre>
 * &#64;Feature('request.headers')
 * def "default headers reach the server over #protocol"() {
 *     ...
 *     where:
 *     protocol << Protocol.available()
 * }
 * </pre>
 */
@Timeout(60)
abstract class IntegrationSpec extends Specification {

    @Shared
    FeatureServer server = FeatureServer.get()

    private final List<GroovyHttpClient> clients = []

    /** A client pinned to {@code protocol}; closed automatically after the iteration. */
    GroovyHttpClient client(Protocol protocol, @DelegatesTo(GroovyHttpClient.Builder) Closure cfg = null) {
        def c = server.client(protocol, cfg)
        clients << c
        c
    }

    def cleanup() {
        clients.each { c -> try { c.close() } catch (ignored) { } }
        clients.clear()
    }

    static Throwable rootCause(Throwable t) {
        Throwable c = t
        while (c?.cause != null && c.cause != c) c = c.cause
        c
    }
}
