package org.softwood.url

import spock.lang.Specification

/** URLBuilder encoding and DSL behaviour that docs/chapters/04-url-builder.adoc relies on (port, path slash, encoding, multi-value params, closure DSL). */
class URLBuilderDocExamplesSpec extends Specification {

    def "fluent API example"() {
        expect:
        new URLBuilder("api.example.com")
                .protocol("https")
                .port(8443)
                .path("/v2/search")
                .param("q", "groovy & java")
                .param("limit", 10)
                .build() == "https://api.example.com:8443/v2/search?q=groovy+%26+java&limit=10"
    }

    def "closure DSL examples"() {
        expect:
        new URLBuilder({
            host "api.example.com"
            path "reports"
            params(year: 2026, region: "uk")
        }).build() == "https://api.example.com/reports?year=2026&region=uk"

        new URLBuilder("api.example.com").build {
            path "/status"
        } == "https://api.example.com/status"
    }

    def "multi-valued parameter example"() {
        expect:
        new URLBuilder("api.example.com").param("id", [1, 2, 3]).build() == "https://api.example.com?id=1&id=2&id=3"
    }
}
