package org.softwood.http

import groovy.transform.CompileStatic

/**
 * An RFC 9457 problem details object ({@code application/problem+json}), as returned by
 * {@code HttpResponseException.problem}. Members other than the five standard ones are kept in
 * {@link #extensions}. A missing {@code type} is {@code about:blank} (RFC 9457 section 3.1.1).
 */
@CompileStatic
final class ProblemDetail implements Serializable {

    private static final long serialVersionUID = 1L
    private static final Set<String> STANDARD = ['type', 'title', 'status', 'detail', 'instance'] as Set<String>

    final String type
    final String title
    final Integer status
    final String detail
    final String instance
    final Map<String, Object> extensions

    ProblemDetail(String type, String title, Integer status, String detail, String instance, Map<String, Object> extensions) {
        this.type = type ?: 'about:blank'
        this.title = title
        this.status = status
        this.detail = detail
        this.instance = instance
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(extensions ?: [:]))
    }

    /** From a parsed JSON object; members of the wrong JSON type are treated as absent, as RFC 9457 asks. */
    static ProblemDetail from(Map<?, ?> m) {
        if (m == null) return null
        Map<String, Object> ext = new LinkedHashMap<>()
        m.each { k, v -> if (!STANDARD.contains(k?.toString())) ext.put(k?.toString(), v) }
        new ProblemDetail(str(m.get('type')), str(m.get('title')),
                m.get('status') instanceof Number ? ((Number) m.get('status')).intValue() : null,
                str(m.get('detail')), str(m.get('instance')), ext)
    }

    private static String str(Object v) { v instanceof CharSequence ? v.toString() : null }

    @Override
    String toString() {
        "ProblemDetail(type=${type}, title=${title}, status=${status})".toString()
    }
}
