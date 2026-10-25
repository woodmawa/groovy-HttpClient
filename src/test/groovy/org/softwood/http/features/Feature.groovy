package org.softwood.http.features

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Ties a spec (class level: every feature method in it) or a single feature method to one or more ids in
 * {@code feature-catalogue.groovy}. FeatureCoverageSpec fails the build when an implemented catalogue entry
 * has no tagged spec, or when a tag names an id the catalogue does not hold.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@interface Feature {
    String[] value()
}
