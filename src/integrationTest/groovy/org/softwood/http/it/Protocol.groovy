package org.softwood.http.it

/**
 * The wire protocols the integration matrix proves. HTTP/3 needs a JDK 26+ client (JEP 517); on JDK 25 it is
 * simply absent from {@link #available()}, and the feature report shows it as n/a for that JDK.
 */
enum Protocol {
    HTTP_1_1('HTTP/1.1'),
    HTTP_2('HTTP/2'),
    HTTP_3('HTTP/3')

    /** Prefix of what Jetty reports as {@code connectionMetaData.protocol} (e.g. "HTTP/2.0"). */
    final String serverPrefix

    Protocol(String serverPrefix) { this.serverPrefix = serverPrefix }

    boolean isAvailable() { this != HTTP_3 || Runtime.version().feature() >= 26 }

    static List<Protocol> available() { values().findAll { it.available } }

    @Override
    String toString() { serverPrefix }
}
