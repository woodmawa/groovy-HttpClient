package org.softwood.http


import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class Http2TestServer {
    Server server
    int port
    ServletContextHandler context

    Http2TestServer() {
        context = new ServletContextHandler()
        //set initial context path
        context.contextPath = "/"
    }

    void startServer() {
        server = new Server()

        // HTTP Configuration with HTTPS support
        HttpConfiguration httpConfig = new HttpConfiguration()
        httpConfig.addCustomizer(new SecureRequestCustomizer())

        // SSL context pointing to test keystore
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server()
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks")
        sslContextFactory.setKeyStorePassword("password")
        sslContextFactory.setTrustStorePath("src/test/resources/keystore.jks")
        sslContextFactory.setTrustStorePassword("password")
        sslContextFactory.setEndpointIdentificationAlgorithm(null) // disables hostname verification

        // ALPN (HTTP/2) connection factory
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory()
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig)
        HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfig)

        ServerConnector connector = new ServerConnector(
                server,
                sslContextFactory,
                alpn,
                h2,
                http1
        )
        connector.port = 0 // dynamic port
        server.addConnector(connector)

        // attach the previously configured context
        server.handler = context

        server.start()
        port = connector.localPort
        println "HTTP/2 test server started on port $port"
    }

    void stop() {
        if (server != null) {
            server.stop()
        }
    }

    void addServlet(HttpServlet servlet, String path) {
        if (context == null) throw new IllegalStateException("Server context not initialized")
        context.addServlet(new ServletHolder(servlet), path)
    }
}
