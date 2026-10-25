package org.softwood.http.codec

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/**
 * A parsed media type (RFC 9110 section 8.3.1), with the structured-syntax suffix of RFC 6839 split out.
 *
 * <pre>
 *   application/vnd.tmf+json; charset=UTF-8
 *   type = application, subtype = vnd.tmf+json, suffix = json, params = [charset: UTF-8]
 * </pre>
 *
 * Type, subtype, suffix and parameter names are lower-cased; parameter values keep their case (quotes removed).
 * Also used for registry patterns: {@code *&#47;*}, {@code text&#47;*}, {@code *&#47;*+json}.
 */
@CompileStatic
@EqualsAndHashCode(includes = ['type', 'subtype'])
final class MediaType implements Serializable {

    private static final long serialVersionUID = 1L

    static final MediaType ANY = parse('*/*')

    final String type
    /** Full subtype, suffix included ({@code vnd.tmf+json}). */
    final String subtype
    /** Structured-syntax suffix without the '+' ({@code json}), or null. */
    final String suffix
    final Map<String, String> params

    private MediaType(String type, String subtype, Map<String, String> params) {
        this.type = type
        this.subtype = subtype
        int plus = subtype.lastIndexOf('+')
        this.suffix = plus >= 0 && plus < subtype.length() - 1 ? subtype.substring(plus + 1) : null
        this.params = Collections.unmodifiableMap(params)
    }

    /** Parses a Content-Type style value. Throws IllegalArgumentException when there is no type/subtype. */
    static MediaType parse(String value) {
        if (value == null || !value.trim()) throw new IllegalArgumentException('media type is empty')
        String[] parts = value.split(';')
        String essence = parts[0].trim().toLowerCase(Locale.ROOT)
        int slash = essence.indexOf('/')
        if (slash <= 0 || slash == essence.length() - 1 || essence.indexOf('/', slash + 1) >= 0) {
            throw new IllegalArgumentException("not a media type: '${value}'")
        }
        Map<String, String> params = new LinkedHashMap<>()
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim()
            int eq = p.indexOf('=')
            if (eq <= 0) continue
            String v = p.substring(eq + 1).trim()
            if (v.length() >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length() - 1)
            params.put(p.substring(0, eq).trim().toLowerCase(Locale.ROOT), v)
        }
        new MediaType(essence.substring(0, slash).trim(), essence.substring(slash + 1).trim(), params)
    }

    /** Null-safe parse: null for a null, blank or malformed value. */
    static MediaType parseOrNull(String value) {
        try { value ? parse(value) : null } catch (IllegalArgumentException ignored) { null }
    }

    /** {@code type/subtype} without parameters. */
    String getEssence() { type + '/' + subtype }

    /** The charset parameter, or null when absent or unknown to this JVM. */
    Charset getCharset() {
        String cs = params.get('charset')
        if (!cs) return null
        try { Charset.forName(cs) } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) { null }
    }

    /** This type with its charset parameter set. */
    MediaType withCharset(Charset charset) {
        Map<String, String> p = new LinkedHashMap<>(params)
        p.put('charset', charset.name())
        new MediaType(type, subtype, p)
    }

    boolean isWildcardType() { type == '*' }

    boolean isWildcardSubtype() { subtype == '*' || subtype.startsWith('*+') }

    /**
     * How well this PATTERN matches a concrete type: 0 exact, 1 structured suffix, 2 type wildcard, 3 any;
     * -1 when it does not match. Lower is more specific.
     */
    int matchRank(MediaType concrete) {
        if (concrete == null) return -1
        if (!wildcardType && !wildcardSubtype) {
            return type == concrete.type && subtype == concrete.subtype ? 0 : -1
        }
        if (subtype.startsWith('*+')) {
            boolean typeOk = wildcardType || type == concrete.type
            return typeOk && suffix != null && suffix == concrete.suffix ? 1 : -1
        }
        if (!wildcardType && subtype == '*') return type == concrete.type ? 2 : -1
        return wildcardType && subtype == '*' ? 3 : -1
    }

    @Override
    String toString() {
        params ? essence + params.collect { k, v -> '; ' + k + '=' + v }.join('') : essence
    }
}
