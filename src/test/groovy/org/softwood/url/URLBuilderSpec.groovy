package org.softwood.url

import spock.lang.Specification
import spock.lang.Unroll

class URLBuilderSpec extends Specification {

    def "Default constructor should initialize with https protocol and empty params"() {
        when:
        def builder = new URLBuilder()

        then:
        builder.@protocol == 'https'
        builder.@host == null
        builder.@port == null
        builder.@path == ''
        builder.@params == [:]
    }

    def "Constructor with host should set the host value"() {
        given:
        def host = "example.com"

        when:
        def builder = new URLBuilder(host)

        then:
        builder.@host == host
        builder.@protocol == 'https'
    }

    def "Constructor with closure should apply configuration"() {
        when:
        def builder = new URLBuilder ().with {
            host "example.com"
            port 8080
        }

        then:
        builder.@host == "example.com"
        builder.@port == 8080
    }

    def "Setting protocol should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.protocol("http")

        then:
        result.is(builder)
        builder.@protocol == "http"
    }

    def "Setting host should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.host("example.com")

        then:
        result.is(builder)
        builder.@host == "example.com"
    }

    def "Setting port should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.port(8080)

        then:
        result.is(builder)
        builder.@port == 8080
    }

    def "Setting basePath should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.path("/api/v1")

        then:
        result.is(builder)
        builder.@path == "/api/v1"
    }

    def "Adding a single param should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.param("key", "value")

        then:
        result.is(builder)
        builder.@params == [key: "value"]
    }

    def "Adding multiple params should return the builder for chaining"() {
        given:
        def builder = new URLBuilder()

        when:
        def result = builder.params([key1: "value1", key2: "value2"])

        then:
        result.is(builder)
        builder.@params == [key1: "value1", key2: "value2"]
    }

    def "Building URL without host should throw exception"() {
        given:
        def builder = new URLBuilder()

        when:
        builder.build()

        then:
        thrown(IllegalStateException)
    }

    def "Building simple URL should work correctly"() {
        given:
        def builder = new URLBuilder("example.com")

        when:
        def url = builder.build()

        then:
        url == "https://example.com"
    }

    def "Building URL with all components should work correctly"() {
        given:
        def builder = new URLBuilder("example.com")
                .protocol("http")
                .port(8080)
                .path("/api/v1")
                .param("sort", "asc")
                .param("page", 1)

        when:
        def url = builder.build()

        then:
        url == "http://example.com:8080/api/v1?sort=asc&page=1"
    }

    def "Building URL with closure configuration should work"() {
        given:
        def builder = new URLBuilder("example.com")

        when:
        def url = builder.build {
            protocol "http"
            port 8080
            param "q", "search term"
        }

        then:
        url == "http://example.com:8080?q=search+term"
    }

    def "basePath should be properly formatted"() {
        when:
        def urlWithSlash = new URLBuilder("example.com").path("/api").build()
        def urlWithoutSlash = new URLBuilder("example.com").path("api").build()

        then:
        urlWithSlash == "https://example.com/api"
        urlWithoutSlash == "https://example.com/api"
    }

    @Unroll
    def "URL encoding should work for parameter '#paramName' with value '#paramValue'"() {
        given:
        def builder = new URLBuilder("example.com")
                .param(paramName, paramValue)

        when:
        def url = builder.build()

        then:
        url == expectedUrl

        where:
        paramName   | paramValue       | expectedUrl
        "q"         | "hello world"    | "https://example.com?q=hello+world"
        "special"   | "a&b=c"          | "https://example.com?special=a%26b%3Dc"
        "unicode"   | "résumé"         | "https://example.com?unicode=r%C3%A9sum%C3%A9"
    }

    def "Array parameters should be properly encoded"() {
        given:
        def builder = new URLBuilder("example.com")
                .param("tags", ["java", "groovy", "spock"] as String[])

        when:
        def url = builder.build()

        then:
        url == "https://example.com?tags=java&tags=groovy&tags=spock"
    }

    def "Collection parameters should be properly encoded"() {
        given:
        def builder = new URLBuilder("example.com")
                .param("ids", [1, 2, 3])

        when:
        def url = builder.build()

        then:
        url == "https://example.com?ids=1&ids=2&ids=3"
    }

    def "Range parameters should be properly encoded"() {
        given:
        def builder = new URLBuilder("example.com")
                .param("years", 2020..2023)

        when:
        def url = builder.build()

        then:
        url == "https://example.com?years=2020&years=2021&years=2022&years=2023"
    }

    def "toString() should return the same result as build()"() {
        given:
        def builder = new URLBuilder("example.com")
                .param("q", "test")

        when:
        def buildResult = builder.build()
        def toStringResult = builder.toString()

        then:
        buildResult == toStringResult
        toStringResult == "https://example.com?q=test"
    }

    def "Multiple parameter types should be correctly encoded"() {
        given:
        def builder = new URLBuilder ().with {
            host "api.example.com"
            path "/search"
            param "q", "test query"
            param "filters", ["active", "verified"]
            param "range", 1..3
        }

        when:
        def url = builder.build()

        then:
        url == "https://api.example.com/search?q=test+query&filters=active&filters=verified&range=1&range=2&range=3"
    }
}