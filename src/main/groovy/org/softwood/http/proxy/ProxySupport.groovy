package org.softwood.http.proxy

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Proxy support for the client (2.1). The JDK HttpClient speaks HTTP proxies only (CONNECT for https targets):
 * it silently ignores a SOCKS proxy and connects directly (JDK-8214516), so SOCKS is refused when configured and
 * warned about when the JVM's default selector supplies one. Internal API.
 */
@CompileStatic
@Slf4j
final class ProxySupport {

    static final String SOCKS_REFUSAL = 'SOCKS proxies are not supported: the JDK HttpClient ignores them and connects ' +
            'directly (JDK-8214516), which would bypass the proxy silently. Use an HTTP proxy.'

    private ProxySupport() {}

    /** An explicit HTTP proxy for every target except those matching a nonProxyHosts pattern. */
    @CompileStatic
    static final class FixedProxySelector extends ProxySelector {
        private final Proxy proxy
        private final List<Pattern> bypass

        FixedProxySelector(String host, int port, Collection<String> nonProxyHosts) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port))
            this.bypass = (nonProxyHosts ?: []).collect { String p -> glob(p) }
        }

        @Override
        List<Proxy> select(URI uri) {
            String host = uri?.host?.toLowerCase(Locale.ROOT)
            if (host != null && bypass.any { Pattern p -> p.matcher(host).matches() }) return [Proxy.NO_PROXY]
            [proxy]
        }

        @Override
        void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }

        private static Pattern glob(String p) {
            String q = p.trim().toLowerCase(Locale.ROOT)
            Pattern.compile(q.split('\\*', -1).collect { String part -> Pattern.quote(part) }.join('.*'))
        }
    }

    /** Wraps the JVM default selector: a SOCKS choice would go direct, so say so once instead of staying silent. */
    @CompileStatic
    static final class SocksWarningSelector extends ProxySelector {
        private final ProxySelector delegate
        private final AtomicBoolean warned = new AtomicBoolean(false)

        SocksWarningSelector(ProxySelector delegate) { this.delegate = delegate }

        @Override
        List<Proxy> select(URI uri) {
            List<Proxy> chosen = delegate.select(uri)
            if (chosen?.any { Proxy p -> p.type() == Proxy.Type.SOCKS } && warned.compareAndSet(false, true)) {
                log.warn('[GroovyHttpClient] The JVM proxy settings select a SOCKS proxy for {}. ' + SOCKS_REFUSAL +
                        ' Requests are going direct.', uri?.host)
            }
            chosen
        }

        @Override
        void connectFailed(URI uri, SocketAddress sa, IOException ioe) { delegate.connectFailed(uri, sa, ioe) }
    }

    /**
     * The preemptive Proxy-Authorization value for proxyAuth (Basic, RFC 7617). The client adds it only to requests
     * the explicit proxy carries. Not a java.net.Authenticator: installing one makes the JDK turn a target's 401 that
     * it declines into IOException("No credentials provided"), losing the 401 response for every request.
     */
    static String basicProxyAuthorization(String user, String password) {
        'Basic ' + Base64.encoder.encodeToString((user + ':' + password).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }

    /** The selector for a client: none, an explicit HTTP proxy, or the JVM default behind the SOCKS warning. */
    static ProxySelector selectorFor(boolean noProxy, String host, Integer port, Collection<String> nonProxyHosts) {
        if (noProxy) return HttpClient.Builder.NO_PROXY
        if (host) return new FixedProxySelector(host, port, nonProxyHosts)
        ProxySelector jvmDefault = ProxySelector.getDefault()
        jvmDefault == null ? HttpClient.Builder.NO_PROXY : new SocksWarningSelector(jvmDefault)
    }

    /** True when a failure is the JDK refusing a tunnel because the proxy answered CONNECT with 407. */
    static boolean isTunnel407(Throwable t) {
        Throwable c = t
        while (c != null) {
            if (c instanceof IOException && c.message?.contains('407')) return true
            c = c.cause
        }
        false
    }

    static final String TUNNEL_407_MESSAGE = 'The proxy refused the HTTPS tunnel (407 Proxy Authentication Required).'

    /**
     * Why an HTTPS tunnel was refused. The JDK drops Basic proxy credentials from CONNECT unless the JVM clears
     * jdk.http.auth.tunneling.disabledSchemes (net.properties default: Basic) -- a JVM-wide setting this library
     * leaves alone. Only a -D override is visible here; the net.properties default is not a system property.
     */
    static String tunnelRefusedMessage(boolean credentialsSet) {
        if (!credentialsSet) return TUNNEL_407_MESSAGE + ' Set proxyAuth(user, password).'
        String disabled = System.getProperty('jdk.http.auth.tunneling.disabledSchemes')
        if (disabled != null && !disabled.toLowerCase(Locale.ROOT).contains('basic')) {
            return TUNNEL_407_MESSAGE + ' The proxy did not accept the proxyAuth credentials.'
        }
        TUNNEL_407_MESSAGE + ' The JDK does not send Basic proxy credentials on CONNECT by default: start the JVM with ' +
                '-Djdk.http.auth.tunneling.disabledSchemes="" (a JVM-wide setting), or check the credentials.'
    }
}
