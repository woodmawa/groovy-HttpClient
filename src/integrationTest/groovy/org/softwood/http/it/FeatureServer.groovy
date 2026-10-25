package org.softwood.http.it

import groovy.json.JsonOutput
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.Fields
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.softwood.http.GroovyHttpClient
import org.softwood.http.Http3Discovery
import org.softwood.http.HttpVersion

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyStore
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The real-server fixture for the integration matrix: ONE Jetty 12.1 server per test JVM, started on first use
 * and stopped by a shutdown hook -- never restarted. (quiche: starting a second HTTP/3 server in one JVM after
 * stopping the first crashes the process natively on Windows, 0xC0000005; and its debug log must stay off, see
 * quietQuiche.)
 *
 * Connectors (all on 127.0.0.1, ephemeral ports, certificate CN=localhost from src/test/resources/keystore.jks):
 *   tls    -- TLS with ALPN h2 + http/1.1  (Protocol.HTTP_1_1, Protocol.HTTP_2)
 *   quic   -- QUIC / HTTP/3, only when the JVM is JDK 26+ (Protocol.HTTP_3)
 *   clear  -- cleartext HTTP/1.1, for insecure-scheme and cross-origin tests
 *
 * Endpoints (same on every connector):
 *   /echo                  any method; JSON {method, path, query, headers, body, protocol}; X-Echo-Method, X-Multi a/b
 *   /status/{code}         that status, body "body-for-{code}"; ?type=&body= override content type and body
 *   /redirect/{n}?code=c   n hops (default 302) ending at /echo
 *   /slow?ms=n             sleeps n ms, then "slow"
 *   /bytes?n=n             n bytes of 'x' (application/octet-stream)
 *   /json, /xml            fixed documents naming the protocol
 *   /gzip, /deflate        compressible text, encoded when Accept-Encoding allows it
 *   /sse, /ndjson, /lines  small streams
 *   /auth/basic            200 for it-user:it-pass, else 401 + WWW-Authenticate Basic
 *   /auth/bearer           200 for "Bearer it-token", else 401 + WWW-Authenticate Bearer
 */
class FeatureServer {

    static final String PASSWORD = 'password'
    static final String BASIC_USER = 'it-user'
    static final String BASIC_PASS = 'it-pass'
    static final String BEARER_TOKEN = 'it-token'
    static final String COMPRESSIBLE = ('hello compression ' * 200)

    private static FeatureServer instance

    static synchronized FeatureServer get() {
        if (instance == null) {
            quietQuiche()
            instance = new FeatureServer()
            Runtime.runtime.addShutdownHook(new Thread({ instance.server.stop() } as Runnable, 'feature-server-stop'))
        }
        instance
    }

    final Server server
    final int tlsPort
    final int clearPort
    final Integer quicPort
    /** Trust store holding only the test certificate, for clients. */
    final KeyStore trustStore

    private FeatureServer() {
        URL keyStoreUrl = FeatureServer.getResource('/keystore.jks')
        KeyStore ks = KeyStore.getInstance('JKS')
        keyStoreUrl.withInputStream { ks.load(it, PASSWORD.toCharArray()) }
        trustStore = KeyStore.getInstance('PKCS12')
        trustStore.load(null, null)
        trustStore.setCertificateEntry('test-server', ks.getCertificate('wiremock'))

        server = new Server()

        def plainConfig = new HttpConfiguration()
        def tlsConfig = new HttpConfiguration()
        tlsConfig.addCustomizer(new SecureRequestCustomizer(false))

        // cleartext HTTP/1.1
        def clear = new ServerConnector(server, new HttpConnectionFactory(plainConfig))
        clear.host = '127.0.0.1'
        server.addConnector(clear)

        // TLS: ALPN chooses h2 or http/1.1
        def h1 = new HttpConnectionFactory(tlsConfig)
        def h2 = new HTTP2ServerConnectionFactory(tlsConfig)
        def alpn = new ALPNServerConnectionFactory()
        alpn.defaultProtocol = h1.protocol
        def tls = new ServerConnector(server, new SslConnectionFactory(sslFactory(keyStoreUrl), alpn.protocol), alpn, h2, h1)
        tls.host = '127.0.0.1'
        server.addConnector(tls)

        // QUIC / HTTP/3 (the JDK 25 run never loads quiche)
        QuicheServerConnector quic = null
        if (Runtime.version().feature() >= 26) {
            def pemDir = Files.createTempDirectory('feature-server-quic')
            def quicConfig = HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(pemDir))
            quic = new QuicheServerConnector(server, sslFactory(keyStoreUrl), quicConfig, new HTTP3ServerConnectionFactory(tlsConfig))
            quic.host = '127.0.0.1'
            quic.port = 0
            server.addConnector(quic)
        }

        server.handler = new Routes()
        server.start()
        clearPort = clear.localPort
        tlsPort = tls.localPort
        quicPort = quic?.localPort
    }

    /**
     * quiche's native debug log is an FFM upcall per packet. With it enabled (any logger at DEBUG under
     * org.eclipse.jetty.quic), a packet logged on one thread while another thread writes to the same QUIC
     * connection kills the JVM on Windows with no hs_err file (Jetty 12.1.14, quiche-foreign). Seen twice on
     * 2026-10-25 at the same matrix iteration; never with the log at INFO. Pin it here so a debug logging
     * config cannot take the suite down.
     */
    private static void quietQuiche() {
        def factory = org.slf4j.LoggerFactory.ILoggerFactory
        if (factory instanceof ch.qos.logback.classic.LoggerContext) {
            ((ch.qos.logback.classic.LoggerContext) factory).getLogger('org.eclipse.jetty.quic').level = ch.qos.logback.classic.Level.INFO
        }
    }

    private static SslContextFactory.Server sslFactory(URL keyStoreUrl) {
        def ssl = new SslContextFactory.Server()
        ssl.keyStorePath = keyStoreUrl.toExternalForm()
        ssl.keyStorePassword = PASSWORD
        ssl.keyManagerPassword = PASSWORD
        ssl
    }

    String baseUrl(Protocol p) {
        if (!p.available) throw new IllegalStateException("${p} needs JDK 26+")
        p == Protocol.HTTP_3 ? "https://localhost:${quicPort}" : "https://localhost:${tlsPort}"
    }

    String clearBaseUrl() { "http://localhost:${clearPort}" }

    /**
     * A client pinned to {@code p}, trusting the test certificate. {@code cfg} runs against the
     * {@link GroovyHttpClient.Builder} before build(), for per-test settings (maxResponseBytes, ...).
     */
    GroovyHttpClient client(Protocol p, @DelegatesTo(GroovyHttpClient.Builder) Closure cfg = null) {
        def b = GroovyHttpClient.builder(baseUrl(p)).trustStore(trustStore)
        switch (p) {
            case Protocol.HTTP_1_1: b.httpVersion(HttpVersion.HTTP_1_1); break
            case Protocol.HTTP_2:   b.httpVersion(HttpVersion.HTTP_2); break
            case Protocol.HTTP_3:   b.http3(Http3Discovery.HTTP_3_URI_ONLY); break
        }
        if (cfg) {
            cfg.delegate = b
            cfg.resolveStrategy = Closure.DELEGATE_FIRST
            cfg.call(b)
        }
        b.build()
    }

    // ---- routes ---------------------------------------------------------------------------------------------

    private static class Routes extends Handler.Abstract {

        @Override
        boolean handle(Request request, Response response, Callback callback) {
            String path = Request.getPathInContext(request)
            Map<String, List<String>> q = queryParams(request)
            String protocol = request.connectionMetaData.protocol
            response.headers.put('X-Protocol', protocol)

            switch (path) {
                case '/echo':
                    return echo(request, response, callback, path, q, protocol)
                case ~'/status/\\d{3}':
                    int code = path.substring('/status/'.length()) as int
                    String type = q.type?.first() ?: 'text/plain; charset=UTF-8'
                    String body = q.body?.first() ?: "body-for-${code}".toString()
                    return send(request, response, callback, code, type, body)
                case ~'/redirect/\\d+':
                    int n = path.substring('/redirect/'.length()) as int
                    int code = (q.code?.first() ?: '302') as int
                    response.headers.put('Location', n <= 1 ? '/echo' : "/redirect/${n - 1}?code=${code}".toString())
                    return send(request, response, callback, code, 'text/plain; charset=UTF-8', "redirect ${n}")
                case '/slow':
                    Thread.sleep((q.ms?.first() ?: '1000') as long)
                    return send(request, response, callback, 200, 'text/plain; charset=UTF-8', 'slow')
                case '/bytes':
                    byte[] bytes = new byte[(q.n?.first() ?: '1024') as int]
                    Arrays.fill(bytes, (byte) 'x')
                    return send(request, response, callback, 200, 'application/octet-stream', bytes)
                case '/json':
                    return send(request, response, callback, 200, 'application/json',
                            JsonOutput.toJson([greeting: 'hello', protocol: protocol]))
                case '/xml':
                    return send(request, response, callback, 200, 'application/xml; charset=UTF-8',
                            "<greeting protocol=\"${protocol}\">hello</greeting>")
                case '/gzip':
                case '/deflate':
                    return compressed(request, response, callback, path.substring(1), q)
                case '/sse':
                    return send(request, response, callback, 200, 'text/event-stream',
                            'event: greeting\ndata: one\n\ndata: two\n\nid: 3\ndata: three\n\n')
                case '/ndjson':
                    return send(request, response, callback, 200, 'application/x-ndjson',
                            '{"n":1}\n{"n":2}\n{"n":3}\n')
                case '/drip':
                    return drip(response, callback, (q.n?.first() ?: '5') as int, (q.gapMs?.first() ?: '100') as long)
                case '/sink':
                    return sink(request, response, callback)
                case '/lines':
                    return send(request, response, callback, 200, 'text/plain; charset=UTF-8', 'alpha\nbeta\ngamma\n')
                case '/auth/basic':
                    String expected = 'Basic ' + "${BASIC_USER}:${BASIC_PASS}".bytes.encodeBase64().toString()
                    if (request.headers.get('Authorization') == expected) {
                        return send(request, response, callback, 200, 'text/plain; charset=UTF-8', 'authorised')
                    }
                    response.headers.put('WWW-Authenticate', 'Basic realm="it"')
                    return send(request, response, callback, 401, 'text/plain; charset=UTF-8', 'unauthorised')
                case '/auth/bearer':
                    if (request.headers.get('Authorization') == "Bearer ${BEARER_TOKEN}".toString()) {
                        return send(request, response, callback, 200, 'text/plain; charset=UTF-8', 'authorised')
                    }
                    response.headers.put('WWW-Authenticate', 'Bearer realm="it"')
                    return send(request, response, callback, 401, 'text/plain; charset=UTF-8', 'unauthorised')
                default:
                    return send(request, response, callback, 404, 'text/plain; charset=UTF-8', "no route ${path}")
            }
        }

        private static boolean echo(Request request, Response response, Callback callback, String path,
                                    Map<String, List<String>> q, String protocol) {
            byte[] body = Content.Source.asInputStream(request).readAllBytes()
            Map<String, List<String>> headers = [:]
            for (HttpField f : request.headers) {
                headers.computeIfAbsent(f.name.toLowerCase(Locale.ROOT)) { [] } << f.value
            }
            response.headers.put('X-Echo-Method', request.method)
            response.headers.add('X-Multi', 'a')
            response.headers.add('X-Multi', 'b')
            send(request, response, callback, 200, 'application/json', JsonOutput.toJson([
                    method  : request.method,
                    path    : path,
                    query   : q,
                    headers : headers,
                    body    : new String(body, StandardCharsets.UTF_8),
                    bodyBase64: Base64.encoder.encodeToString(body),
                    protocol: protocol]))
        }

        /** ?n= sends n bytes of 'x' instead of the text: a tiny encoded body that inflates hugely (zip-bomb specs). */
        private static boolean compressed(Request request, Response response, Callback callback, String kind,
                                          Map<String, List<String>> q) {
            String accept = request.headers.get('Accept-Encoding') ?: ''
            byte[] plain
            if (q.n) {
                plain = new byte[q.n.first() as int]
                Arrays.fill(plain, (byte) 'x')
            } else {
                plain = COMPRESSIBLE.getBytes(StandardCharsets.UTF_8)
            }
            if (!accept.toLowerCase(Locale.ROOT).contains(kind)) {
                return send(request, response, callback, 200, 'text/plain; charset=UTF-8', plain)
            }
            def bos = new ByteArrayOutputStream()
            def out = kind == 'gzip' ? new GZIPOutputStream(bos) : new DeflaterOutputStream(bos, new Deflater())
            out.withStream { it.write(plain) }
            response.headers.put('Content-Encoding', kind)
            response.headers.put('Vary', 'Accept-Encoding')
            send(request, response, callback, 200, 'text/plain; charset=UTF-8', bos.toByteArray())
        }

        /** n lines 'drip i', one every gapMs, each flushed as it is written. A client that stops reading ends it. */
        private static boolean drip(Response response, Callback callback, int n, long gapMs) {
            response.status = 200
            response.headers.put('Content-Type', 'text/plain; charset=UTF-8')
            try {
                for (int i = 1; i <= n; i++) {
                    def done = new org.eclipse.jetty.util.FutureCallback()
                    response.write(false, ByteBuffer.wrap("drip ${i}\n".toString().getBytes(StandardCharsets.UTF_8)), done)
                    done.get()
                    if (i < n) Thread.sleep(gapMs)
                }
                response.write(true, null, callback)
            } catch (Exception e) {
                callback.failed(e)   // the client went away (stop, idle timeout)
            }
            true
        }

        /** Consumes the request body without keeping it; answers its length and SHA-256 (upload specs). */
        private static boolean sink(Request request, Response response, Callback callback) {
            def digest = java.security.MessageDigest.getInstance('SHA-256')
            long length = 0L
            InputStream body = Content.Source.asInputStream(request)
            byte[] buf = new byte[65_536]
            int n
            while ((n = body.read(buf)) > 0) {
                digest.update(buf, 0, n)
                length += n
            }
            Map<String, String> h = [:]
            for (HttpField f : request.headers) h[f.name.toLowerCase(Locale.ROOT)] = f.value
            send(request, response, callback, 200, 'application/json', JsonOutput.toJson([
                    method : request.method,
                    length : length,
                    sha256 : digest.digest().encodeHex().toString(),
                    headers: h]))
        }

        private static Map<String, List<String>> queryParams(Request request) {
            Map<String, List<String>> q = [:]
            Fields fields = Request.extractQueryParameters(request)
            for (Fields.Field f : fields) q[f.name] = f.values
            q
        }

        private static boolean send(Request request, Response response, Callback callback, int status, String contentType, String body) {
            send(request, response, callback, status, contentType, body.getBytes(StandardCharsets.UTF_8))
        }

        private static boolean send(Request request, Response response, Callback callback, int status, String contentType, byte[] body) {
            response.status = status
            if (contentType) response.headers.put('Content-Type', contentType)
            if (request.method == 'HEAD') {
                response.headers.put('Content-Length', String.valueOf(body.length))
                response.write(true, null, callback)
            } else {
                response.write(true, ByteBuffer.wrap(body), callback)
            }
            true
        }
    }
}
