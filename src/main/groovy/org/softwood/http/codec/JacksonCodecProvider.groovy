package org.softwood.http.codec

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.CompileStatic
import org.softwood.http.GroovyHttpClient.ContentParseException

import java.nio.charset.StandardCharsets

/**
 * Optional Jackson 2.x support (com.fasterxml.jackson), found by ServiceLoader when jackson-databind is on the
 * classpath; the library itself only compiles against it.
 *
 * Scope (2.1 design decision 1): Jackson handles TYPED JSON results ({@code bodyAs(Customer)}) and encoding of
 * objects that are not Maps, collections, strings or scalars. Untyped parsing ({@code parsed}, {@code json})
 * and Map/List encoding stay on JsonSlurper / JsonOutput, so 2.0 callers see the same types and bytes.
 */
@CompileStatic
class JacksonCodecProvider implements CodecProvider {

    static final String MAPPER_CLASS = 'com.fasterxml.jackson.databind.ObjectMapper'

    @Override
    boolean isAvailable() {
        try {
            Class.forName(MAPPER_CLASS, false, JacksonCodecProvider.classLoader)
            true
        } catch (ClassNotFoundException | LinkageError ignored) {
            false
        }
    }

    @Override
    void register(CodecRegistry.Builder registry) {
        ObjectMapper mapper = new ObjectMapper()
        mapper.findAndRegisterModules()
        registerWith(registry, mapper)
    }

    /** Registers Jackson JSON codecs on the builder's current tier (user tier from {@code codecs { jackson m }}). */
    static void registerWith(CodecRegistry.Builder registry, Object objectMapper) {
        if (!(objectMapper instanceof ObjectMapper)) {
            throw new IllegalArgumentException("jackson(...) needs a com.fasterxml.jackson.databind.ObjectMapper, got ${objectMapper?.getClass()?.name}")
        }
        ObjectMapper mapper = (ObjectMapper) objectMapper
        Encoder enc = { Object body, MediaType t -> encode(mapper, body, t) } as Encoder
        Parser par = { byte[] body, MediaType t, Class<?> target -> parse(mapper, body, t, target) } as Parser
        ['application/json', '*/*+json'].each { String p -> registry.encoder(p, enc); registry.parser(p, par) }
    }

    private static boolean plain(Object body) {
        body == null || body instanceof CharSequence || body instanceof byte[] || body instanceof Map ||
                body instanceof Collection || body instanceof Number || body instanceof Boolean || body.getClass().isArray()
    }

    static byte[] encode(ObjectMapper mapper, Object body, MediaType t) {
        if (plain(body)) return BuiltinCodecs.jsonEncode(body, t)
        try {
            byte[] utf8 = mapper.writeValueAsBytes(body)
            def cs = t?.charset
            return cs == null || cs == StandardCharsets.UTF_8 ? utf8 : new String(utf8, StandardCharsets.UTF_8).getBytes(cs)
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Jackson could not encode ${body.getClass().name}: ${e.originalMessage}", e)
        }
    }

    static Object parse(ObjectMapper mapper, byte[] body, MediaType t, Class<?> target) {
        if (target == Object || target == String || target == byte[] || Map.isAssignableFrom(target) ||
                Collection.isAssignableFrom(target)) {
            return BuiltinCodecs.jsonParse(body, t, target)
        }
        try {
            def cs = t?.charset
            return cs == null || cs == StandardCharsets.UTF_8 ? mapper.readValue(body, target) : mapper.readValue(CodecRegistry.text(body, t), target)
        } catch (JacksonException e) {
            throw new ContentParseException("Cannot map JSON to ${target.name} (from ${t?.essence}): ${e.originalMessage}", e)
        } catch (IOException e) {
            throw new ContentParseException("Cannot map JSON to ${target.name} (from ${t?.essence}): ${e.message}", e)
        }
    }
}
