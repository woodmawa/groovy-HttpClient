package org.softwood.http

import groovy.transform.PackageScope
import groovy.util.logging.Slf4j

import java.lang.reflect.Method
import java.net.http.HttpClient
import java.net.http.HttpRequest

/*
 * Licensed under the Apache License, Version 2.0 -- see LICENSE_APACHE_2.0.txt
 */

/**
 * Runtime bridge to the JDK 26+ HTTP/3 API (JEP 517).
 *
 * <p>The library is compiled to Java 25 bytecode, where {@code HttpClient.Version.HTTP_3},
 * {@code java.net.http.HttpOption} and {@code HttpRequest.Builder.setOption} do not exist. They are looked
 * up once, reflectively; when absent, HTTP/3 is reported unavailable and callers fall back.</p>
 */
@Slf4j
@PackageScope
final class Http3Support {

    private static final HttpClient.Version JDK_HTTP_3
    private static final Object H3_DISCOVERY_OPTION          // java.net.http.HttpOption<Http3DiscoveryMode>
    private static final Class<? extends Enum> MODE_TYPE      // java.net.http.HttpOption$Http3DiscoveryMode
    private static final Method SET_OPTION                    // HttpRequest.Builder.setOption(HttpOption, Object)

    static {
        HttpClient.Version v = HttpClient.Version.values().find { it.name() == 'HTTP_3' }
        Object option = null
        Class<? extends Enum> mode = null
        Method setOption = null
        if (v != null) {
            try {
                Class<?> optionType = Class.forName('java.net.http.HttpOption')
                option = optionType.getField('H3_DISCOVERY').get(null)
                mode = (Class<? extends Enum>) Class.forName('java.net.http.HttpOption$Http3DiscoveryMode')
                setOption = HttpRequest.Builder.getMethod('setOption', optionType, Object)
            } catch (ReflectiveOperationException | LinkageError e) {
                log.debug('HTTP/3 version present but discovery option API not found: {}', e.toString())
                option = null; mode = null; setOption = null
            }
        }
        JDK_HTTP_3 = v
        H3_DISCOVERY_OPTION = option
        MODE_TYPE = mode
        SET_OPTION = setOption
    }

    private Http3Support() {}

    /** True when the running JDK's HttpClient supports HTTP/3 (JDK 26+). */
    static boolean isAvailable() {
        JDK_HTTP_3 != null && SET_OPTION != null
    }

    /**
     * The JDK version constant for a library version, or null when HTTP/3 is requested on a JDK without it
     * (callers decide whether that is a fallback or an error).
     */
    static HttpClient.Version toJdk(HttpVersion v) {
        switch (v) {
            case HttpVersion.HTTP_1_1: return HttpClient.Version.HTTP_1_1
            case HttpVersion.HTTP_2:   return HttpClient.Version.HTTP_2
            case HttpVersion.HTTP_3:   return isAvailable() ? JDK_HTTP_3 : null
            default:                   return null
        }
    }

    /** Library view of a JDK response version ("HTTP_1_1", "HTTP_2", "HTTP_3"). */
    static HttpVersion fromJdk(HttpClient.Version v) {
        v == null ? null : HttpVersion.values().find { it.name() == v.name() }
    }

    /** Set the H3_DISCOVERY request option. No-op when HTTP/3 is unavailable or mode is null. */
    static void applyDiscovery(HttpRequest.Builder b, Http3Discovery mode) {
        if (mode == null || !isAvailable()) return
        SET_OPTION.invoke(b, H3_DISCOVERY_OPTION, Enum.valueOf(MODE_TYPE, mode.name()))
    }
}
