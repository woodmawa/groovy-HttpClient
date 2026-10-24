package org.softwood.http

/*
 * Licensed under the Apache License, Version 2.0 -- see LICENSE_APACHE_2.0.txt
 */

/**
 * How the client discovers that a server speaks HTTP/3. Mirrors
 * {@code java.net.http.HttpOption.Http3DiscoveryMode} (JDK 26+, JEP 517).
 *
 * <ul>
 *   <li>{@link #ANY} -- try HTTP/3 and HTTP/2 (or HTTP/1.1) and use whichever connects; falls back.</li>
 *   <li>{@link #ALT_SVC} -- start on HTTP/2 or HTTP/1.1 and switch to HTTP/3 only when the server advertises
 *       it with an {@code Alt-Svc} header. Safest for general use.</li>
 *   <li>{@link #HTTP_3_URI_ONLY} -- HTTP/3 only, no fallback. Fails if the server does not answer over QUIC.
 *       A client configured with this mode refuses to be built on a JDK without HTTP/3.</li>
 * </ul>
 *
 * @since 2.0.0
 */
enum Http3Discovery {
    ANY,
    ALT_SVC,
    HTTP_3_URI_ONLY
}
