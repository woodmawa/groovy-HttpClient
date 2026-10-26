package org.softwood.http.codec

import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlSlurper
import groovy.xml.XmlUtil
import groovy.xml.slurpersupport.GPathResult
import org.softwood.http.GroovyHttpClient.ContentParseException
import org.xml.sax.SAXException

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The codecs every registry starts with. JSON uses Groovy's JsonOutput / JsonSlurper (2.0 behaviour); XML parses
 * with DOCTYPE declarations and external entities refused (XXE). Typed results go through {@link TypedMapper}.
 */
@CompileStatic
final class BuiltinCodecs {

    private BuiltinCodecs() {}

    static void register(CodecRegistry.Builder b) {
        Encoder json = { Object body, MediaType t -> jsonEncode(body, t) } as Encoder
        Parser jsonParser = { byte[] body, MediaType t, Class<?> target -> jsonParse(body, t, target) } as Parser
        ['application/json', '*/*+json'].each { String p -> b.encoder(p, json); b.parser(p, jsonParser) }

        Encoder xml = { Object body, MediaType t -> xmlEncode(body, t) } as Encoder
        Parser xmlParser = { byte[] body, MediaType t, Class<?> target -> xmlParse(body, t, target) } as Parser
        ['application/xml', 'text/xml', '*/*+xml'].each { String p -> b.encoder(p, xml); b.parser(p, xmlParser) }

        Encoder text = { Object body, MediaType t -> textEncode(body, t) } as Encoder
        Parser textParser = { byte[] body, MediaType t, Class<?> target -> textParse(body, t, target) } as Parser
        ['text/plain', 'text/*'].each { String p -> b.encoder(p, text); b.parser(p, textParser) }

        b.encoder('application/x-www-form-urlencoded', { Object body, MediaType t -> formEncode(body, t) } as Encoder)
        b.parser('application/x-www-form-urlencoded', { byte[] body, MediaType t, Class<?> target ->
            TypedMapper.map(formDecode(CodecRegistry.text(body, t)), target, t)
        } as Parser)

        b.encoder('text/csv', { Object body, MediaType t -> CodecRegistry.bytes(Csv.encode(body), t) } as Encoder)
        b.parser('text/csv', { byte[] body, MediaType t, Class<?> target ->
            target == String ? CodecRegistry.text(body, t) : TypedMapper.map(Csv.parse(CodecRegistry.text(body, t)), target, t)
        } as Parser)

        b.encoder('application/octet-stream', { Object body, MediaType t ->
            if (body instanceof byte[]) return (byte[]) body
            throw new IllegalArgumentException("application/octet-stream bodies must be byte[], got ${body?.getClass()?.name}")
        } as Encoder)
        b.parser('application/octet-stream', { byte[] body, MediaType t, Class<?> target ->
            target == String ? CodecRegistry.text(body, t) : body
        } as Parser)

        // Unknown response types: text (or the raw bytes when asked for byte[]). Requests get no fallback.
        b.parser('*/*', { byte[] body, MediaType t, Class<?> target -> textParse(body, t, target) } as Parser)
    }

    /* ------------------------------------------------------------------ JSON */

    static byte[] jsonEncode(Object body, MediaType t) {
        if (body instanceof byte[]) return (byte[]) body
        if (body instanceof CharSequence) return CodecRegistry.bytes((CharSequence) body, t)   // sent verbatim
        CodecRegistry.bytes(JsonOutput.toJson(body), t)
    }

    /** JsonSlurper parse (Map / List / scalars); null for an empty body. */
    static Object jsonUntyped(byte[] body, MediaType t) {
        String s = CodecRegistry.text(body, t)
        if (!s.trim()) return null
        try {
            new JsonSlurper().parseText(s)
        } catch (JsonException e) {
            throw new ContentParseException("Response is not valid JSON (Content-Type=${t}): ${e.message}", e)
        }
    }

    static Object jsonParse(byte[] body, MediaType t, Class<?> target) {
        if (target == String) return CodecRegistry.text(body, t)
        if (target == byte[]) return body
        TypedMapper.map(jsonUntyped(body, t), target, t)
    }

    /* ------------------------------------------------------------------ XML */

    static byte[] xmlEncode(Object body, MediaType t) {
        if (body instanceof byte[]) return (byte[]) body
        if (body instanceof CharSequence) return CodecRegistry.bytes((CharSequence) body, t)
        Charset cs = t?.charset ?: StandardCharsets.UTF_8
        if (body instanceof Closure) {
            def smb = new StreamingMarkupBuilder()
            smb.encoding = cs.name()
            // A markup closure written inside a request closure has that closure as its owner, whose delegate is the
            // (strict) request DSL: owner-first resolution would send order(...) there. Delegate-first on a copy
            // lets the markup builder see every element name first.
            Closure markup = (Closure) ((Closure) body).clone()
            markup.resolveStrategy = Closure.DELEGATE_FIRST
            return smb.bind(markup).toString().getBytes(cs)
        }
        if (body instanceof GPathResult) return XmlUtil.serialize((GPathResult) body).getBytes(cs)
        throw new IllegalArgumentException("XML bodies must be a String, a markup Closure or a GPathResult, got ${body?.getClass()?.name}")
    }

    static Object xmlParse(byte[] body, MediaType t, Class<?> target) {
        if (target == String) return CodecRegistry.text(body, t)
        if (target == byte[]) return body
        GPathResult r
        try {
            r = secureXmlSlurper().parseText(CodecRegistry.text(body, t))
        } catch (SAXException e) {
            throw new ContentParseException("Response is not valid XML (Content-Type=${t}): ${e.message}", e)
        }
        if (target == Object || target.isInstance(r)) return r
        throw new ContentParseException("No typed XML mapping to ${target.name}: register a parser for '${t.essence}'")
    }

    /** XmlSlurper with DOCTYPE declarations refused and external entities off (XXE, billion laughs). */
    static XmlSlurper secureXmlSlurper() {
        def s = new XmlSlurper(false, true, false)
        s.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        s.setFeature('http://xml.org/sax/features/external-general-entities', false)
        s.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
        s
    }

    /* ------------------------------------------------------------------ text / form */

    static byte[] textEncode(Object body, MediaType t) {
        if (body instanceof byte[]) return (byte[]) body
        CodecRegistry.bytes(String.valueOf(body), t)
    }

    static Object textParse(byte[] body, MediaType t, Class<?> target) {
        if (target == byte[]) return body
        String s = CodecRegistry.text(body, t)
        if (target == Object || target == String || target == CharSequence) return s
        throw new ContentParseException("Cannot map ${t?.essence ?: 'untyped'} text to ${target.name}")
    }

    static byte[] formEncode(Object body, MediaType t) {
        if (body instanceof byte[]) return (byte[]) body
        if (body instanceof CharSequence) return CodecRegistry.bytes((CharSequence) body, t)
        if (!(body instanceof Map)) throw new IllegalArgumentException("form bodies must be a Map, got ${body?.getClass()?.name}")
        CodecRegistry.bytes(formString((Map) body, t?.charset ?: StandardCharsets.UTF_8), t)
    }

    /** k=v&amp;k=v, URL-encoded with the charset; null values skipped, Iterables repeat the key. */
    static String formString(Map<?, ?> params, Charset charset) {
        List<String> pairs = []
        params.each { k, v ->
            if (v == null) return
            String key = URLEncoder.encode(k.toString(), charset)
            if (v instanceof Iterable) {
                ((Iterable) v).each { vv -> if (vv != null) pairs << key + '=' + URLEncoder.encode(vv.toString(), charset) }
            } else {
                pairs << key + '=' + URLEncoder.encode(v.toString(), charset)
            }
        }
        pairs.join('&')
    }

    static Map<String, List<String>> formDecode(String s) {
        Map<String, List<String>> out = new LinkedHashMap<>()
        if (!s) return out
        for (String pair : s.split('&')) {
            if (!pair) continue
            int eq = pair.indexOf('=')
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8)
            String v = eq < 0 ? '' : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
            out.computeIfAbsent(k) { new ArrayList<String>() }.add(v)
        }
        out
    }
}
