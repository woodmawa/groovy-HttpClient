package org.softwood.http.features

import org.spockframework.runtime.model.FeatureMetadata
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The static half of the release gate: compares the catalogue with the @Feature tags on the compiled specs of
 * both source sets (the unit specs in test, the real-server specs in integrationTest). The runtime half --
 * did each feature actually pass on each protocol and JDK -- is the featureGate Gradle task.
 *
 * Specs in this package (the harness itself) are exempt.
 */
class FeatureCoverageSpec extends Specification {

    @Shared FeatureCatalogue catalogue = FeatureCatalogue.load()
    @Shared Map<String, Set<String>> unitTags        // feature id -> 'Spec.method' locations
    @Shared Map<String, Set<String>> integrationTags
    @Shared List<String> untagged = []

    def setupSpec() {
        unitTags = scan('feature.scan.unitDirs')
        integrationTags = scan('feature.scan.integrationDirs')
    }

    def "the catalogue loads and every entry is well formed"() {
        expect:
        catalogue.entries.size() > 0
    }

    def "every @Feature tag names a catalogue entry"() {
        given:
        def unknown = (unitTags.keySet() + integrationTags.keySet()) - catalogue.ids()

        expect:
        unknown.isEmpty()
    }

    def "every implemented feature has at least one tagged spec"() {
        given:
        def uncovered = catalogue.entries.findAll { it.implemented }*.id.findAll { !unitTags[it] && !integrationTags[it] }

        expect:
        uncovered.isEmpty()
    }

    def "every implemented integration feature has a real-server spec"() {
        given:
        def uncovered = catalogue.entries.findAll { it.implemented && it.integration }*.id.findAll { !integrationTags[it] }

        expect:
        uncovered.isEmpty()
    }

    def "every spec feature method is tagged"() {
        expect:
        untagged.isEmpty()
    }

    private Map<String, Set<String>> scan(String property) {
        String dirs = System.getProperty(property)
        assert dirs: "system property ${property} is not set -- run through the integrationTest task"
        Map<String, Set<String>> tags = [:].withDefault { new TreeSet<String>() }
        dirs.split(File.pathSeparator).collect { Paths.get(it) }.findAll { Files.isDirectory(it) }.each { Path root ->
            Files.walk(root).withCloseable { stream ->
                stream.filter { it.toString().endsWith('.class') && !it.fileName.toString().contains('$') }.each { Path cls ->
                    String name = root.relativize(cls).toString().replace(File.separator, '.') - ~/\.class$/
                    Class<?> type = Class.forName(name, false, getClass().classLoader)
                    if (!Specification.isAssignableFrom(type) || type.package?.name == FeatureCoverageSpec.package.name) return
                    List<String> classIds = []
                    for (Class<?> c = type; c != null && c != Specification; c = c.superclass) {
                        c.getAnnotation(Feature)?.value()?.each { classIds << it }
                    }
                    type.declaredMethods.findAll { Method m -> m.isAnnotationPresent(FeatureMetadata) }.each { Method m ->
                        String where = "${type.simpleName}.${m.getAnnotation(FeatureMetadata).name()}"
                        def ids = classIds + (m.getAnnotation(Feature)?.value()?.toList() ?: [])
                        if (ids.isEmpty()) untagged << where.toString()
                        ids.each { tags[it] << where.toString() }
                    }
                }
            }
        }
        tags
    }
}
