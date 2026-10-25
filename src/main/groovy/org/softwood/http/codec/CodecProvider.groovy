package org.softwood.http.codec

import groovy.transform.CompileStatic

/**
 * ServiceLoader SPI ({@code META-INF/services/org.softwood.http.codec.CodecProvider}): contributes codecs to every
 * registry built with {@link CodecRegistry#defaults()} or {@link CodecRegistry.Builder}. Provider codecs rank
 * above the built-ins and below anything the user registers. A provider whose dependencies are missing at
 * runtime (e.g. Jackson not on the classpath) is skipped, never fatal.
 */
@CompileStatic
interface CodecProvider {
    /** False when this provider's optional dependencies are absent; it is then not registered. */
    boolean isAvailable()

    void register(CodecRegistry.Builder registry)
}
