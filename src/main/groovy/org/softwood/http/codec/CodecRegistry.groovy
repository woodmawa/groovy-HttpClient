package org.softwood.http.codec

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Picks an {@link Encoder} for a request body and a {@link Parser} for a response body by media type.
 *
 * Immutable once built: one registry per client, shared by every request and thread.
 *
 * Matching, most specific first (see {@link MediaType#matchRank}):
 * <ol>
 *   <li>exact {@code type/subtype}, e.g. {@code application/vnd.tmf+json}</li>
 *   <li>structured suffix, e.g. {@code *&#47;*+json} matches {@code application/problem+json}</li>
 *   <li>type wildcard, e.g. {@code text&#47;*}</li>
 *   <li>{@code *&#47;*}: text for responses; requests have no fallback encoder</li>
 * </ol>
 * At the same rank: user registrations beat ServiceLoader providers, which beat the built-ins; within a tier,
 * the later registration wins.
 */
@Slf4j
@CompileStatic
final class CodecRegistry {

    static final int TIER_BUILTIN = 0
    static final int TIER_PROVIDER = 1
    static final int TIER_USER = 2

    private static final class Entry<T> {
        final MediaType pattern
        final T codec
        final int tier
        final int seq

        Entry(MediaType pattern, T codec, int tier, int seq) {
            this.pattern = pattern; this.codec = codec; this.tier = tier; this.seq = seq
        }
    }

    private final List<Entry<Encoder>> encoders
    private final List<Entry<Parser>> parsers

    private CodecRegistry(List<Entry<Encoder>> encoders, List<Entry<Parser>> parsers) {
        this.encoders = Collections.unmodifiableList(new ArrayList<>(encoders))
        this.parsers = Collections.unmodifiableList(new ArrayList<>(parsers))
    }

    /** Built-ins plus every available ServiceLoader provider. Built once per class loader. */
    static CodecRegistry defaults() { Defaults.INSTANCE }

    private static final class Defaults {
        static final CodecRegistry INSTANCE = new Builder().build()
    }

    static Builder builder() { new Builder() }

    /** The encoder for this request Content-Type, or null when none is registered. */
    Encoder encoderFor(MediaType type) { best(encoders, type) }

    Encoder encoderFor(String contentType) { encoderFor(MediaType.parseOrNull(contentType)) }

    /** The parser for this response Content-Type (a missing type is treated as {@code *&#47;*}). */
    Parser parserFor(MediaType type) { best(parsers, type ?: MediaType.ANY) }

    Parser parserFor(String contentType) { parserFor(MediaType.parseOrNull(contentType)) }

    /** Encode with the registered encoder; IllegalArgumentException when no encoder handles the type. */
    byte[] encode(Object body, MediaType type) {
        Encoder e = encoderFor(type)
        if (e == null) throw new IllegalArgumentException("No encoder registered for '${type}'")
        e.encode(body, type)
    }

    /** Parse with the registered parser; target Object.class for an untyped parse. */
    Object parse(byte[] body, MediaType type, Class<?> target = Object) {
        parserFor(type).parse(body ?: new byte[0], type ?: MediaType.ANY, target ?: Object)
    }

    private static <T> T best(List<Entry<T>> entries, MediaType type) {
        if (type == null) return null
        Entry<T> winner = null
        int winnerRank = Integer.MAX_VALUE
        for (Entry<T> e : entries) {
            int r = e.pattern.matchRank(type)
            if (r < 0) continue
            if (winner == null || r < winnerRank ||
                    (r == winnerRank && (e.tier > winner.tier || (e.tier == winner.tier && e.seq > winner.seq)))) {
                winner = e
                winnerRank = r
            }
        }
        winner?.codec
    }

    /** Decodes text with the type's charset, UTF-8 when it has none (shared by codecs). */
    static String text(byte[] body, MediaType type) {
        Charset cs = type?.charset ?: StandardCharsets.UTF_8
        String s = new String(body ?: new byte[0], cs)
        s.startsWith('\uFEFF') ? s.substring(1) : s
    }

    /** Encodes text with the type's charset, UTF-8 when it has none (shared by codecs). */
    static byte[] bytes(CharSequence text, MediaType type) {
        text.toString().getBytes(type?.charset ?: StandardCharsets.UTF_8)
    }

    /* ------------------------------------------------------------------------------------------------ */

    /**
     * Collects registrations. The {@code codecs { }} block on {@code GroovyHttpClient.builder(url)} delegates
     * here:
     * <pre>
     * codecs {
     *     parser  'application/vnd.tmf+json', { String text -> ... }     // or { byte[] b, MediaType t, Class c -> }
     *     encoder 'text/csv',                 { body -> ... }            // return byte[] or a CharSequence
     *     jackson myObjectMapper                                         // needs Jackson on the classpath
     * }
     * </pre>
     */
    static final class Builder {
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CodecRegistry)
        private List<Entry<Encoder>> encoders = []
        private List<Entry<Parser>> parsers = []
        private int seq = 0
        private int tier = TIER_USER
        private boolean providers = true

        Builder encoder(String pattern, Encoder encoder) {
            if (encoder == null) throw new IllegalArgumentException('encoder is required')
            encoders << new Entry<Encoder>(MediaType.parse(pattern), encoder, tier, seq++)
            this
        }

        Builder encoder(String pattern, Closure body) { encoder(pattern, adaptEncoder(body)) }

        Builder parser(String pattern, Parser parser) {
            if (parser == null) throw new IllegalArgumentException('parser is required')
            parsers << new Entry<Parser>(MediaType.parse(pattern), parser, tier, seq++)
            this
        }

        Builder parser(String pattern, Closure body) { parser(pattern, adaptParser(body)) }

        /** Use this Jackson ObjectMapper for typed JSON results and object encoding (needs Jackson 2.x). */
        Builder jackson(Object objectMapper) {
            JacksonCodecProvider.registerWith(this, objectMapper)
            this
        }

        /** Leave ServiceLoader providers (e.g. the Jackson auto-detection) out of this registry. */
        Builder withoutProviders() { providers = false; this }

        /** Builds a registry; the builder is unchanged and can build again. */
        CodecRegistry build() {
            List<Entry<Encoder>> userEnc = encoders
            List<Entry<Parser>> userPar = parsers
            encoders = []
            parsers = []
            try {
                tier = TIER_BUILTIN
                BuiltinCodecs.register(this)
                if (providers) {
                    tier = TIER_PROVIDER
                    for (CodecProvider p : loadProviders()) {
                        try {
                            p.register(this)
                        } catch (LinkageError | RuntimeException e) {
                            LOG.warn('Codec provider {} skipped: {}', p.class.name, e.toString())
                        }
                    }
                }
                encoders.addAll(userEnc)
                parsers.addAll(userPar)
                return new CodecRegistry(encoders, parsers)
            } finally {
                encoders = userEnc
                parsers = userPar
                tier = TIER_USER
            }
        }

        private static List<CodecProvider> loadProviders() {
            List<CodecProvider> out = []
            Iterator<CodecProvider> it = ServiceLoader.load(CodecProvider, CodecRegistry.classLoader).iterator()
            for (int guard = 0; guard < 64; guard++) {
                try {
                    if (!it.hasNext()) break
                    CodecProvider p = it.next()
                    if (p.available) out << p
                } catch (ServiceConfigurationError | LinkageError e) {
                    LOG.debug('Codec provider not loaded: {}', e.toString())
                }
            }
            out
        }

        private static Encoder adaptEncoder(Closure c) {
            int n = c.maximumNumberOfParameters
            return { Object body, MediaType type ->
                Object out = n >= 2 ? c.call(body, type) : c.call(body)
                if (out instanceof byte[]) return (byte[]) out
                if (out instanceof CharSequence) return CodecRegistry.bytes((CharSequence) out, type)
                throw new IllegalArgumentException("encoder for '${type}' returned ${out?.getClass()?.name}; expected byte[] or a CharSequence")
            } as Encoder
        }

        private static Parser adaptParser(Closure c) {
            int n = c.maximumNumberOfParameters
            boolean wantsText = c.parameterTypes.length > 0 && c.parameterTypes[0] == String
            return { byte[] body, MediaType type, Class<?> target ->
                Object first = wantsText ? CodecRegistry.text(body, type) : body
                n >= 3 ? c.call(first, type, target) : n == 2 ? c.call(first, type) : c.call(first)
            } as Parser
        }
    }
}
