package org.softwood.http

/**
 * The {@code response { }} block of a request (2.1): turns the outcome into a value instead of an exception.
 *
 * <pre>
 * def order = client.getSync(Order, '/orders/42') {
 *     response {
 *         success   { r -> r as Order }
 *         when(404) { r -> null }
 *         when(409, 422) { r -> throw new ConflictException(r.problem) }
 *         when(500..599) { r -> fallbackOrder() }
 *         failure   { r -> throw r.toException() }   // any other status >= 400
 *         exception { Throwable t -> cachedOrder() } // transport, circuit open, size limit, redirect refused
 *     }
 * }
 * </pre>
 *
 * Precedence for a status: {@code when(int)} &gt; {@code when(a, b, ...)} / {@code when([..])} &gt; {@code when(range)}
 * &gt; {@code success} (status &lt; 400) / {@code failure} (&gt;= 400) &gt; the default (return the response below
 * 400, raise HttpResponseException from 400). Handlers see only the final attempt: retries and the circuit
 * breaker run first, unchanged. {@code exception} never receives an HTTP status raise -- that is what the status
 * handlers are for.
 */
class ResponseHandlers {

    private Closure successHandler
    private Closure failureHandler
    private Closure exceptionHandler
    private final Map<Integer, Closure> exact = new LinkedHashMap<>()
    private final List<List<Object>> sets = []     // [Set<Integer>, Closure]
    private final List<List<Object>> ranges = []   // [IntRange, Closure]

    /** Builds handlers from a {@code response { }} block. */
    static ResponseHandlers from(@DelegatesTo(value = ResponseHandlers, strategy = Closure.DELEGATE_FIRST) Closure dsl) {
        def h = new ResponseHandlers()
        if (dsl == null) return h
        Closure c = (Closure) dsl.clone()
        c.delegate = h
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call(h)
        h
    }

    /** Any status below 400 that no when(...) claims. */
    void success(Closure handler) { successHandler = required(handler) }

    /** Any status from 400 that no when(...) claims. */
    void failure(Closure handler) { failureHandler = required(handler) }

    /** Transport errors, circuit open, ResponseTooLargeException, refused redirects -- never a status raise. */
    void exception(Closure handler) { exceptionHandler = required(handler) }

    /** {@code when(404) {}}, {@code when(409, 422) {}}, {@code when([401, 403]) {}}, {@code when(500..599) {}}. */
    void when(Object... args) {
        if (args == null || args.length < 2 || !(args[-1] instanceof Closure)) {
            throw new IllegalArgumentException('when(...) takes one or more status codes, a list or a range, then a closure')
        }
        Closure handler = (Closure) args[-1]
        List<Object> codes = (args as List).subList(0, args.length - 1)
        if (codes.size() == 1 && codes[0] instanceof IntRange) {
            IntRange r = (IntRange) codes[0]
            check(r.from); check(r.to)
            ranges << [r, handler]
            return
        }
        if (codes.size() == 1 && codes[0] instanceof Number) {
            exact.put(check(((Number) codes[0]).intValue()), handler)
            return
        }
        Set<Integer> set = new LinkedHashSet<>()
        codes.each { Object o ->
            if (o instanceof Collection) ((Collection) o).each { set << check(toCode(it)) }
            else set << check(toCode(o))
        }
        sets << [set, handler]
    }

    boolean isEmpty() {
        successHandler == null && failureHandler == null && exceptionHandler == null && !exact && !sets && !ranges
    }

    Closure getExceptionHandler() { exceptionHandler }

    /** The handler for a status by the precedence above, or null for the default behaviour. */
    Closure select(int status) {
        Closure c = exact.get(status)
        if (c != null) return c
        for (List<Object> s : sets) if (((Set<Integer>) s[0]).contains(status)) return (Closure) s[1]
        for (List<Object> r : ranges) if (((IntRange) r[0]).containsWithinBounds(status)) return (Closure) r[1]
        status < 400 ? successHandler : failureHandler
    }

    /** These handlers over a client-level default: per key, this request's handler wins. */
    ResponseHandlers mergedOver(ResponseHandlers defaults) {
        if (defaults == null || defaults.empty) return this
        def m = new ResponseHandlers()
        m.successHandler = successHandler ?: defaults.successHandler
        m.failureHandler = failureHandler ?: defaults.failureHandler
        m.exceptionHandler = exceptionHandler ?: defaults.exceptionHandler
        m.exact.putAll(defaults.exact)
        m.exact.putAll(exact)
        m.sets.addAll(sets); m.sets.addAll(defaults.sets)
        m.ranges.addAll(ranges); m.ranges.addAll(defaults.ranges)
        m
    }

    /** Calls a handler with the argument when it takes one. */
    static Object call(Closure handler, Object arg) {
        handler.maximumNumberOfParameters == 0 ? handler.call() : handler.call(arg)
    }

    private static Closure required(Closure c) {
        if (c == null) throw new IllegalArgumentException('a handler closure is required')
        c
    }

    private static int toCode(Object o) {
        if (o instanceof Number) return ((Number) o).intValue()
        throw new IllegalArgumentException("not a status code: ${o}")
    }

    private static int check(int status) {
        if (status < 100 || status > 599) throw new IllegalArgumentException("status codes are 100..599, got ${status}")
        status
    }
}
