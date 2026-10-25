package org.softwood.http.codec

import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation
import org.softwood.http.GroovyHttpClient.ContentParseException

import java.lang.reflect.Modifier

/**
 * Typed results without Jackson: Groovy coercion. A Map becomes an instance of the target through its no-arg
 * constructor and property setters, recursively for properties whose declared type is a class; scalars and lists
 * go through Groovy's own casting. A key the class has no property for fails with ContentParseException naming
 * the class and the key (the same default as Jackson). Collections keep their element types as parsed.
 */
final class TypedMapper {

    private TypedMapper() {}

    static Object map(Object value, Class<?> target, MediaType type = null) {
        if (target == null || target == Object) return value
        if (value == null) return null
        if (target.isInstance(value)) return value
        if (target == String) return value.toString()
        if (value instanceof Map && !Map.isAssignableFrom(target) && !target.isPrimitive()) {
            return toBean((Map) value, target, type)
        }
        if (value instanceof List && target.isArray()) {
            List<?> list = (List<?>) value
            Object arr = java.lang.reflect.Array.newInstance(target.componentType, list.size())
            list.eachWithIndex { Object v, int i -> java.lang.reflect.Array.set(arr, i, map(v, target.componentType, type)) }
            return arr
        }
        try {
            DefaultTypeTransformation.castToType(value, target)
        } catch (Exception e) {
            throw new ContentParseException("Cannot map ${describe(value)} to ${target.name}${from(type)}", e)
        }
    }

    private static Object toBean(Map<?, ?> m, Class<?> target, MediaType type) {
        if (target.isInterface() || Modifier.isAbstract(target.modifiers)) {
            throw new ContentParseException("Cannot map an object to ${target.name}: it is an interface or abstract class")
        }
        Object bean
        try {
            bean = target.getDeclaredConstructor().newInstance()
        } catch (ReflectiveOperationException e) {
            throw new ContentParseException(
                    "${target.name} needs an accessible no-arg constructor for typed results (or add Jackson to the classpath)", e)
        }
        MetaClass mc = InvokerHelper.getMetaClass(target)
        m.each { k, v ->
            String name = k?.toString()
            MetaProperty p = name ? mc.getMetaProperty(name) : null
            if (p == null || p instanceof groovy.lang.MetaBeanProperty && ((MetaBeanProperty) p).setter == null && ((MetaBeanProperty) p).field == null) {
                throw new ContentParseException("${target.name} has no property '${name}'${from(type)}")
            }
            Object coerced
            try {
                coerced = map(v, p.type, type)
            } catch (ContentParseException e) {
                throw new ContentParseException("${target.name}.${name}: ${e.message}", e)
            }
            try {
                p.setProperty(bean, coerced)
            } catch (Exception e) {
                throw new ContentParseException("Cannot set ${target.name}.${name}: ${e.message}", e)
            }
        }
        bean
    }

    private static String describe(Object v) { v instanceof Map ? 'an object' : v instanceof List ? 'an array' : v.getClass().simpleName }

    private static String from(MediaType t) { t ? " (from ${t.essence})" : '' }
}
