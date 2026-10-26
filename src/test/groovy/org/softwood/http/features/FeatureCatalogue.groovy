package org.softwood.http.features

import groovy.transform.Immutable

/**
 * Every public capability of the library, loaded from the classpath resource {@code feature-catalogue.groovy}.
 * That file is the single source of truth: the coverage gate, the results recorder and the feature report
 * all read it.
 */
class FeatureCatalogue {

    static final List<String> PROTOCOLS = ['HTTP_1_1', 'HTTP_2', 'HTTP_3'].asImmutable()
    static final List<String> STATUSES  = ['implemented', 'planned'].asImmutable()
    private static final Set<String> KEYS = ['id', 'title', 'status', 'version', 'chapter', 'protocols', 'integration', 'minJdk'] as Set

    @Immutable
    static class Entry {
        String id
        String title
        String status            // implemented | planned
        String version           // release that shipped it, or the target release while planned
        String chapter           // docs/guide file stem (user guide chapter), or null
        List<String> protocols   // protocols the integration matrix must prove; [] = protocol-independent
        boolean integration      // true = an integrationTest spec (real Jetty) is required once implemented
        Integer minJdk           // lowest runtime JDK the feature exists on (null = every supported JDK)

        boolean isImplemented() { status == 'implemented' }
    }

    final List<Entry> entries

    private FeatureCatalogue(List<Entry> entries) { this.entries = entries.asImmutable() }

    private static FeatureCatalogue cached

    static synchronized FeatureCatalogue load() {
        if (cached) return cached
        URL url = FeatureCatalogue.getResource('/feature-catalogue.groovy')
        if (url == null) throw new IllegalStateException('feature-catalogue.groovy is not on the test classpath')
        def binding = new Binding([ALL: PROTOCOLS])
        List<Map> raw = (List<Map>) new GroovyShell(binding).evaluate(url.getText('UTF-8'), 'feature-catalogue.groovy')

        def problems = []
        Set<String> seen = [] as Set
        def entries = raw.collect { Map m ->
            def unknownKeys = m.keySet() - KEYS
            if (unknownKeys) problems << "${m.id}: unknown keys ${unknownKeys}".toString()
            if (!(m.id ==~ /[a-z0-9]+(\.[a-z0-9-]+)+/)) problems << "bad id '${m.id}' (area.name, lower case)".toString()
            if (!seen.add(m.id as String)) problems << "duplicate id ${m.id}".toString()
            if (!(m.status in STATUSES)) problems << "${m.id}: status must be one of ${STATUSES}".toString()
            if (!m.title) problems << "${m.id}: title is required".toString()
            List<String> protocols = ((m.protocols ?: []) as List).collect { it as String }
            if (!(protocols - PROTOCOLS).isEmpty()) problems << "${m.id}: unknown protocols ${protocols - PROTOCOLS}".toString()
            if (protocols && !m.integration) problems << "${m.id}: protocols listed but integration is false".toString()
            new Entry(id: m.id, title: m.title, status: m.status, version: m.version as String, chapter: m.chapter as String,
                    protocols: protocols, integration: m.integration as boolean, minJdk: m.minJdk as Integer)
        }
        if (problems) throw new IllegalStateException('feature-catalogue.groovy is invalid:\n  ' + problems.join('\n  '))
        cached = new FeatureCatalogue(entries)
    }

    Entry find(String id) { entries.find { it.id == id } }

    Set<String> ids() { entries*.id as Set }
}
