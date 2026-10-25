package org.softwood.http.features

import groovy.json.JsonOutput
import org.opentest4j.TestAbortedException
import org.spockframework.runtime.AbstractRunListener
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.SpecInfo

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Records, for every iteration of every spec, which catalogue features it exercised, on which protocol and
 * JDK, and whether it passed. One JSON line per iteration in {@code <feature.results.dir>/<spec>.jsonl};
 * the featureReport Gradle task turns these into the feature x protocol x JDK matrix.
 *
 * Inactive unless the test task sets the system property {@code feature.results.dir}.
 * A data variable named {@code protocol} (an enum constant or its name) marks the protocol of an iteration;
 * without one the iteration is recorded as protocol-independent ("-").
 */
class FeatureResultsExtension implements IGlobalExtension {

    @Override
    void visitSpec(SpecInfo spec) {
        String dir = System.getProperty('feature.results.dir')
        if (!dir) return
        Path out = Paths.get(dir).resolve(spec.reflection.name + '.jsonl')
        String task = System.getProperty('feature.results.task', 'unknown')
        int jdk = Runtime.version().feature()

        spec.allFeatures.each { FeatureInfo feature ->
            List<String> ids = featureIds(spec, feature)
            feature.addIterationInterceptor({ IMethodInvocation inv ->
                String outcome = 'PASSED'
                String error = null
                try {
                    inv.proceed()
                } catch (TestAbortedException aborted) {
                    outcome = 'SKIPPED'
                    error = aborted.message
                    throw aborted
                } catch (Throwable t) {
                    outcome = 'FAILED'
                    error = "${t.class.simpleName}: ${t.message}".toString().take(400)
                    throw t
                } finally {
                    Map vars = inv.iteration?.dataVariables ?: [:]
                    def p = vars.protocol
                    String protocol = p == null ? '-' : (p instanceof Enum ? ((Enum) p).name() : String.valueOf(p))
                    write(out, [task: task, jdk: jdk, spec: spec.reflection.name, feature: feature.name,
                                iteration: inv.iteration?.displayName, ids: ids, protocol: protocol,
                                outcome: outcome, error: error])
                }
            } as IMethodInterceptor)
        }

        spec.addListener(new AbstractRunListener() {
            @Override
            void featureSkipped(FeatureInfo feature) {
                write(out, [task: task, jdk: jdk, spec: spec.reflection.name, feature: feature.name, iteration: null,
                            ids: featureIds(spec, feature), protocol: '-', outcome: 'SKIPPED', error: 'feature skipped'])
            }

            @Override
            void specSkipped(SpecInfo skipped) {
                skipped.allFeatures.each { featureSkipped(it) }
            }
        })
    }

    static List<String> featureIds(SpecInfo spec, FeatureInfo feature) {
        Set<String> ids = new LinkedHashSet<>()
        for (SpecInfo s = spec; s != null; s = s.superSpec) {
            s.reflection.getAnnotation(Feature)?.value()?.each { ids << it }
        }
        feature.featureMethod.reflection.getAnnotation(Feature)?.value()?.each { ids << it }
        ids as List
    }

    private static synchronized void write(Path out, Map record) {
        Files.createDirectories(out.parent)
        Files.writeString(out, JsonOutput.toJson(record) + '\n', StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
