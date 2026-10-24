package org.softwood.http.http3

import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.ssl.SslContextFactory

import java.nio.file.Path
import java.security.KeyStore

/**
 * In-process HTTP/3 (QUIC, UDP) server for tests: Jetty 12.1 + Cloudflare quiche (native, bundled for
 * win32/linux/darwin x86-64 and aarch64), using src/test/resources/keystore.jks
 * (self-signed, CN=localhost, SAN dns:localhost ip:127.0.0.1).
 *
 * GET /hello answers "hello over <protocol>" so a test can see which protocol the server saw.
 */
class Http3TestServer implements AutoCloseable {

    static final String PASSWORD = 'password'

    final Server server
    final int port
    /** A trust store containing only the test certificate, for the client side. */
    final KeyStore trustStore

    Http3TestServer(Path pemWorkDir) {
        def keyStoreUrl = Http3TestServer.getResource('/keystore.jks')
        KeyStore ks = KeyStore.getInstance('JKS')
        keyStoreUrl.withInputStream { ks.load(it, PASSWORD.toCharArray()) }
        trustStore = KeyStore.getInstance('PKCS12')
        trustStore.load(null, null)
        trustStore.setCertificateEntry('test-server', ks.getCertificate('wiremock'))

        server = new Server()

        def ssl = new SslContextFactory.Server()
        ssl.keyStorePath = keyStoreUrl.toExternalForm()
        ssl.keyStorePassword = PASSWORD
        ssl.keyManagerPassword = PASSWORD

        def quicConfig = HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(pemWorkDir))

        def httpConfig = new HttpConfiguration()
        httpConfig.addCustomizer(new SecureRequestCustomizer())

        def connector = new QuicheServerConnector(server, ssl, quicConfig, new HTTP3ServerConnectionFactory(httpConfig))
        connector.host = '127.0.0.1'
        connector.port = 0
        server.addConnector(connector)

        server.handler = new Handler.Abstract() {
            @Override
            boolean handle(Request request, Response response, Callback callback) {
                response.headers.put('Content-Type', 'text/plain; charset=UTF-8')
                Content.Sink.write(response, true, "hello over ${request.connectionMetaData.protocol}".toString(), callback)
                true
            }
        }
        server.start()
        port = connector.localPort
    }

    @Override
    void close() {
        server?.stop()
    }
}
