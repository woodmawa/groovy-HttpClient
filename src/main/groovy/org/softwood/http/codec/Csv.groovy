package org.softwood.http.codec

import groovy.transform.CompileStatic
import org.softwood.http.GroovyHttpClient.ContentParseException

/**
 * RFC 4180 CSV. Encode: a List of Maps (header row = the union of keys, in first-seen order) or a List of Lists
 * (no header added); CRLF line endings; fields quoted when they hold a comma, quote, CR, LF or edge spaces.
 * Parse: the first record is the header; each later record becomes a LinkedHashMap. A record with more fields
 * than the header fails; a shorter one leaves the missing keys null.
 */
@CompileStatic
final class Csv {

    private Csv() {}

    static String encode(Object body) {
        if (body instanceof CharSequence) return body.toString()
        if (!(body instanceof Iterable)) {
            throw new IllegalArgumentException("CSV bodies must be a List of Maps or Lists, got ${body?.getClass()?.name}")
        }
        List<Object> rows = ((Iterable) body).toList()
        StringBuilder sb = new StringBuilder()
        if (!rows) return ''
        if (rows[0] instanceof Map) {
            LinkedHashSet<Object> header = new LinkedHashSet<>()
            rows.each { Object r -> header.addAll(((Map) r).keySet()) }
            line(sb, header.collect { it?.toString() })
            rows.each { Object r -> Map m = (Map) r; line(sb, header.collect { m.get(it) }) }
        } else {
            rows.each { Object r -> line(sb, (r instanceof Iterable) ? ((Iterable) r).toList() : [r]) }
        }
        sb.toString()
    }

    private static void line(StringBuilder sb, List<?> fields) {
        sb.append(fields.collect { quote(it) }.join(',')).append('\r\n')
    }

    private static String quote(Object v) {
        String s = v == null ? '' : v.toString()
        boolean needs = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r') || s != s.trim()
        needs ? '"' + s.replace('"', '""') + '"' : s
    }

    static List<Map<String, String>> parse(String text) {
        List<List<String>> records = records(text)
        if (!records) return []
        List<String> header = records[0]
        List<Map<String, String>> out = []
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records[i]
            if (r.size() > header.size()) {
                throw new ContentParseException("CSV record ${i + 1} has ${r.size()} fields; the header has ${header.size()}")
            }
            Map<String, String> m = new LinkedHashMap<>()
            for (int c = 0; c < header.size(); c++) m.put(header[c], c < r.size() ? r[c] : null)
            out << m
        }
        out
    }

    /** All records as field lists; a trailing line break does not make an empty record. */
    static List<List<String>> records(String text) {
        List<List<String>> out = []
        if (text == null || text.isEmpty()) return out
        List<String> rec = []
        StringBuilder field = new StringBuilder()
        boolean quoted = false
        boolean any = false
        int i = 0
        int n = text.length()
        while (i < n) {
            char ch = text.charAt(i)
            if (quoted) {
                if (ch == '"' as char) {
                    if (i + 1 < n && text.charAt(i + 1) == '"' as char) { field.append('"'); i += 2; continue }
                    quoted = false
                } else {
                    field.append(ch)
                }
                i++
                continue
            }
            if (ch == '"' as char && field.length() == 0) { quoted = true; any = true }
            else if (ch == ',' as char) { rec << field.toString(); field.setLength(0); any = true }
            else if (ch == '\r' as char || ch == '\n' as char) {
                if (ch == '\r' as char && i + 1 < n && text.charAt(i + 1) == '\n' as char) i++
                rec << field.toString(); field.setLength(0)
                out << rec
                rec = []
                any = false
            } else { field.append(ch); any = true }
            i++
        }
        if (quoted) throw new ContentParseException('CSV ends inside a quoted field')
        if (any || field.length() > 0 || !rec.isEmpty()) { rec << field.toString(); out << rec }
        out
    }
}
