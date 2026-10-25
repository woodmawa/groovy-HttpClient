package org.softwood.http.it

import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.ConnectHandler

import java.nio.charset.StandardCharsets

/** A Jetty HTTP CONNECT proxy for the proxy specs: records every tunnel and can require Basic proxy auth. */
class TestProxy implements AutoCloseable {
    final Server server = new Server()
    final int port
    final List<String> connects = Collections.synchronizedList(new ArrayList<String>())
    final List<String> refused = Collections.synchronizedList(new ArrayList<String>())

    TestProxy(String user = null, String password = null) {
        String required = user == null ? null :
                'Basic ' + Base64.encoder.encodeToString("${user}:${password}".toString().getBytes(StandardCharsets.UTF_8))
        def connector = new ServerConnector(server)
        connector.port = 0
        server.addConnector(connector)
        def tunnels = connects
        def denied = refused
        server.handler = new ConnectHandler() {
            @Override
            protected boolean handleAuthentication(Request request, Response response, String address) {
                if (required == null || request.headers.get('Proxy-Authorization') == required) {
                    tunnels << address
                    return true
                }
                denied << address
                response.headers.put('Proxy-Authenticate', 'Basic realm="it-proxy"')
                false
            }
        }
        server.start()
        port = connector.localPort
    }

    @Override
    void close() { server.stop() }
}
