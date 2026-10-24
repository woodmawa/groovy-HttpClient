package org.softwood.http

/*
 * Licensed under the Apache License, Version 2.0 -- see LICENSE_APACHE_2.0.txt
 */

/**
 * HTTP protocol version preference for {@link GroovyHttpClient}.
 *
 * <p>Defined by this library (rather than using {@code java.net.http.HttpClient.Version} directly) so that
 * {@link #HTTP_3} can be named on every supported JDK. HTTP/3 needs JDK 26 or later at runtime
 * (JEP 517); see {@link GroovyHttpClient#isHttp3Available()}.</p>
 *
 * @since 2.0.0
 */
enum HttpVersion {
    HTTP_1_1,
    HTTP_2,
    /** HTTP/3 over QUIC. JDK 26+ only; on older runtimes the client falls back to HTTP/2 (see {@link Http3Discovery}). */
    HTTP_3
}
