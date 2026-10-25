package org.softwood.http.codec

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import groovy.json.JsonOutput
import groovy.xml.slurpersupport.GPathResult
import org.softwood.http.GroovyHttpClient
import org.softwood.http.GroovyHttpClient.ContentParseException
import org.softwood.http.SecurityConfig
import org.softwood.http.features.Feature
import org.softwood.test.MockHttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class CodecRegistrySpec extends Specification {

    static class Customer {
        String id
        String name
        int age
        Address address
    }

    static class Address {
        String city
    }

    static class NoDefaultCtor {
        final String id
        NoDefaultCtor(String id) { this.id = id }
    }

    static class Annotated {
        @JsonProperty('cust_id') String id
    }

    static byte[] utf8(String s) { s.getBytes(StandardCharsets.UTF_8) }

    // ================================================================ codec.registry

    @Feature('codec.registry')
    def "MediaType parses type, subtype, suffix and parameters"() {
        when:
        def t = MediaType.parse('Application/VND.TMF+JSON; Charset="ISO-8859-1"; q=0.5')

        then:
        t.type == 'application'
        t.subtype == 'vnd.tmf+json'
        t.suffix == 'json'
        t.essence == 'application/vnd.tmf+json'
        t.params == [charset: 'ISO-8859-1', q: '0.5']
        t.charset == StandardCharsets.ISO_8859_1
        MediaType.parse('text/plain').suffix == null
        MediaType.parseOrNull('nonsense') == null
        MediaType.parseOrNull(null) == null
    }

    @Feature('codec.registry')
    def "a malformed media type is refused"() {
        when:
        MediaType.parse(value)

        then:
        thrown(IllegalArgumentException)

        where:
        value << ['', 'json', 'application/', '/json', 'a/b/c']
    }

    @Feature('codec.registry')
    def "pattern #pattern ranks #rank against #concrete"() {
        expect:
        MediaType.parse(pattern).matchRank(MediaType.parse(concrete)) == rank

        where:
        pattern             | concrete                      || rank
        'application/json'  | 'application/json'            || 0
        'application/json'  | 'application/problem+json'    || -1
        '*/*+json'          | 'application/problem+json'    || 1
        'application/*+json'| 'application/vnd.tmf+json'    || 1
        'application/*+json'| 'text/foo+json'               || -1
        '*/*+json'          | 'application/xml'             || -1
        'text/*'            | 'text/csv'                    || 2
        'text/*'            | 'application/csv'             || -1
        '*/*'               | 'image/png'                   || 3
    }

    @Feature('codec.registry')
    def "the most specific registration wins, then user over built-in, then the later one"() {
        given:
        def reg = CodecRegistry.builder().withoutProviders()
                .parser('application/*', { byte[] b -> 'user-type-wildcard' })
                .parser('*/*+json', { byte[] b -> 'user-suffix-first' })
                .parser('*/*+json', { byte[] b -> 'user-suffix-second' })
                .build()

        expect: 'exact built-in beats a user suffix pattern'
        reg.parse(utf8('{"a":1}'), MediaType.parse('application/json')) == [a: 1]

        and: 'user beats built-in at the suffix rank; the later user registration wins'
        reg.parse(utf8('{}'), MediaType.parse('application/problem+json')) == 'user-suffix-second'

        and: 'type wildcard applies when nothing more specific does'
        reg.parse(utf8('x'), MediaType.parse('application/pdf')) == 'user-type-wildcard'

        and: 'unknown types fall back to text'
        reg.parse(utf8('héllo'), MediaType.parse('image/x-made-up')) == 'héllo'
        reg.parse(utf8('raw'), null) == 'raw'
    }

    @Feature('codec.registry')
    def "requests have no fallback encoder"() {
        given:
        def reg = CodecRegistry.defaults()

        expect:
        reg.encoderFor('image/png') == null

        when:
        reg.encode([a: 1], MediaType.parse('image/png'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('image/png')
    }

    @Feature('codec.registry')
    def "closure codecs: a String first parameter gets decoded text; encoders may return text or bytes"() {
        given:
        def reg = CodecRegistry.builder()
                .parser('text/x-upper', { String s -> s.toUpperCase() })
                .parser('text/x-meta', { byte[] b, MediaType t, Class c -> "${b.length}:${t.essence}:${c.simpleName}".toString() })
                .encoder('text/x-rev', { body -> body.toString().reverse() })
                .encoder('text/x-bytes', { body, t -> [1, 2, 3] as byte[] })
                .build()

        expect:
        reg.parse(utf8('abc'), MediaType.parse('text/x-upper')) == 'ABC'
        reg.parse(utf8('abc'), MediaType.parse('text/x-meta'), String) == '3:text/x-meta:String'
        new String(reg.encode('abc', MediaType.parse('text/x-rev')), StandardCharsets.UTF_8) == 'cba'
        reg.encode('x', MediaType.parse('text/x-bytes')) == [1, 2, 3] as byte[]
    }

    @Feature('codec.registry')
    def "a builder can build more than once without duplicating or losing registrations"() {
        given:
        def b = CodecRegistry.builder().parser('text/x-one', { String s -> 'one' })

        when:
        def first = b.build()
        def second = b.build()

        then:
        first.parse(utf8(''), MediaType.parse('text/x-one')) == 'one'
        second.parse(utf8(''), MediaType.parse('text/x-one')) == 'one'
        second.parse(utf8('{"a":1}'), MediaType.parse('application/json')) == [a: 1]
    }

    @Feature('codec.registry')
    def "text uses the charset parameter and drops a UTF-8 BOM"() {
        expect:
        CodecRegistry.defaults().parse('café'.getBytes(StandardCharsets.ISO_8859_1), MediaType.parse('text/plain; charset=ISO-8859-1')) == 'café'
        CodecRegistry.defaults().parse(utf8('\uFEFFhi'), MediaType.parse('text/plain')) == 'hi'
    }

    @Feature('codec.registry')
    def "XML parses with DOCTYPE refused (no XXE) and encodes markup closures"() {
        given:
        def reg = CodecRegistry.defaults()
        def xmlType = MediaType.parse('application/xml')

        expect:
        (reg.parse(utf8('<a><b>1</b></a>'), xmlType) as GPathResult).b.text() == '1'
        new String(reg.encode({ order { id 7 } }, xmlType), StandardCharsets.UTF_8) == '<order><id>7</id></order>'

        when:
        reg.parse(utf8('<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><a>&x;</a>'), xmlType)

        then:
        thrown(ContentParseException)
    }

    @Feature('codec.registry')
    def "form bodies round-trip through the registry"() {
        given:
        def reg = CodecRegistry.defaults()
        def t = MediaType.parse('application/x-www-form-urlencoded; charset=UTF-8')

        expect:
        reg.parse(reg.encode([a: '1', b: ['x y', 'z&'], skip: null], t), t) == [a: ['1'], b: ['x y', 'z&']]
    }

    @Feature('codec.registry')
    def "invalid JSON is a ContentParseException"() {
        when:
        CodecRegistry.defaults().parse(utf8('{oops'), MediaType.parse('application/json'))

        then:
        thrown(ContentParseException)
    }

    @Feature('codec.registry')
    def "client: codecs { } on the builder parses responses and body(obj, type) encodes requests"() {
        given:
        def server = new MockHttpServer()
        server.init()
        server.addRequestCheck('GET', '/tmf', 200)
                .withResponseBody('a|b|c')
                .withResponseHeaders(['Content-Type': 'application/x-pipes'])
        def post = server.addRequestCheck('POST', '/upload', 201)
        def client = GroovyHttpClient.builder("http://localhost:${server.port}").codecs {
            parser 'application/x-pipes', { String s -> s.split(/\|/) as List }
        }.build()

        when:
        def r = client.getSync('/tmf')
        client.postSync('/upload', null) { body([[sku: 'A', qty: 2]], 'text/csv; charset=UTF-8') }

        then:
        r.parsed == ['a', 'b', 'c']
        post.lastRequest.header('Content-Type') == 'text/csv; charset=UTF-8'
        post.lastRequest.bodyText == 'sku,qty\r\nA,2\r\n'

        cleanup:
        client?.close()
        server.shutdown()
    }

    @Feature('response.xml')
    def "response.xml refuses a DOCTYPE with an external entity (XXE)"() {
        given:
        def server = new MockHttpServer()
        server.init()
        server.addRequestCheck('GET', '/x', 200)
                .withResponseBody('<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "file:///C:/Windows/win.ini">]><a>&x;</a>')
                .withResponseHeaders(['Content-Type': 'application/xml'])
        def client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))

        when:
        client.getSync('/x').xml

        then: 'refused for the DOCTYPE itself, not some other failure'
        def e = thrown(Exception)
        e.message.toUpperCase().contains('DOCTYPE')

        cleanup:
        client?.close()
        server.shutdown()
    }

    // ================================================================ codec.structured-suffix

    @Feature('codec.structured-suffix')
    def "#contentType parses through the #kind built-in"() {
        expect:
        CodecRegistry.defaults().parse(utf8(body), MediaType.parse(contentType)).getClass().name.contains(expectedClass)

        where:
        contentType                                   | body                        | kind  || expectedClass
        'application/problem+json'                    | '{"title":"x"}'             | 'JSON'|| 'Map'
        'application/vnd.tmf+json; version=4'         | '[1,2]'                     | 'JSON'|| 'List'
        'APPLICATION/VND.API+JSON'                    | '{}'                        | 'JSON'|| 'Map'
        'application/atom+xml'                        | '<feed/>'                   | 'XML' || 'NodeChild'
        'application/soap+xml; charset=utf-8'         | '<e/>'                      | 'XML' || 'NodeChild'
    }

    // ================================================================ codec.typed-result

    @Feature('codec.typed-result')
    def "without Jackson a JSON object maps to a bean, nested beans and number coercion included"() {
        given:
        def reg = CodecRegistry.builder().withoutProviders().build()

        when:
        Customer c = reg.parse(utf8('{"id":"c1","name":"Ann","age":41,"address":{"city":"Leeds"}}'),
                MediaType.parse('application/json'), Customer) as Customer

        then:
        c.id == 'c1'
        c.age == 41
        c.address.city == 'Leeds'
    }

    @Feature('codec.typed-result')
    def "without Jackson, an unknown key or a class with no no-arg constructor fails naming the class"() {
        given:
        def reg = CodecRegistry.builder().withoutProviders().build()

        when:
        reg.parse(utf8('{"id":"c1","shoeSize":9}'), MediaType.parse('application/json'), Customer)

        then:
        def e1 = thrown(ContentParseException)
        e1.message.contains(Customer.name)
        e1.message.contains('shoeSize')

        when:
        reg.parse(utf8('{"id":"x"}'), MediaType.parse('application/json'), NoDefaultCtor)

        then:
        def e2 = thrown(ContentParseException)
        e2.message.contains(NoDefaultCtor.name)
    }

    @Feature('codec.typed-result')
    def "typed results through the client: getSync(Type), bodyAs and the as operator"() {
        given:
        def server = new MockHttpServer()
        server.init()
        server.addRequestCheck('GET', '/c', 200)
                .withResponseBody('{"id":"c1","name":"Ann","age":41}')
                .withResponseHeaders(['Content-Type': 'application/vnd.tmf+json'])
        def client = new GroovyHttpClient(SecurityConfig.testing("http://localhost:${server.port}"))

        when:
        Customer viaVerb = client.getSync(Customer, '/c')
        def r = client.getSync('/c')

        then:
        viaVerb.name == 'Ann'
        r.bodyAs(Customer).age == 41
        (r as Customer).id == 'c1'
        (r as Map).name == 'Ann'
        (r as String) == r.body
        r.parsed == [id: 'c1', name: 'Ann', age: 41]

        cleanup:
        client?.close()
        server.shutdown()
    }

    // ================================================================ codec.csv

    @Feature('codec.csv')
    def "CSV encodes a list of maps with a header row and RFC 4180 quoting"() {
        expect:
        Csv.encode([[id: 1, note: 'plain'], [id: 2, note: 'a, "quoted"\nline', extra: ' x']]) ==
                'id,note,extra\r\n1,plain,\r\n2,"a, ""quoted""\nline"," x"\r\n'
        Csv.encode([['a', 'b'], [1, null]]) == 'a,b\r\n1,\r\n'
    }

    @Feature('codec.csv')
    def "CSV parses quoted fields, embedded line breaks, LF or CRLF, and a missing final break"() {
        expect:
        Csv.parse('id,note\r\n1,"a, ""b""\r\nc"\n2,plain') == [[id: '1', note: 'a, "b"\r\nc'], [id: '2', note: 'plain']]
        Csv.parse('id,note\n3\n') == [[id: '3', note: null]]
        Csv.parse('') == []
    }

    @Feature('codec.csv')
    def "CSV round-trips through the registry and refuses malformed input"() {
        given:
        def t = MediaType.parse('text/csv; charset=UTF-8')
        def rows = [[sku: 'A-1', desc: 'Widget, large'], [sku: 'B-2', desc: 'Café "deluxe"']]

        expect:
        CodecRegistry.defaults().parse(CodecRegistry.defaults().encode(rows, t), t) == rows

        when:
        Csv.parse('a,b\n1,2,3\n')

        then:
        thrown(ContentParseException)

        when:
        Csv.parse('a\n"unterminated\n')

        then:
        thrown(ContentParseException)
    }

    // ================================================================ codec.jackson

    @Feature('codec.jackson')
    def "the Jackson provider is found by ServiceLoader and maps typed results with Jackson annotations"() {
        expect:
        new JacksonCodecProvider().available
        (CodecRegistry.defaults().parse(utf8('{"cust_id":"c9"}'), MediaType.parse('application/json'), Annotated) as Annotated).id == 'c9'
    }

    @Feature('codec.jackson')
    def "with Jackson present, untyped parsing and Map encoding stay on JsonSlurper / JsonOutput"() {
        given:
        def reg = CodecRegistry.defaults()
        def t = MediaType.parse('application/json')
        def map = [b: 2, a: [1, 'x'], when: 'now']

        expect:
        reg.parse(utf8('{"a":1}'), t).getClass().name.endsWith('groovy.json.internal.LazyMap')   // JsonSlurper's map
        new String(reg.encode(map, t), StandardCharsets.UTF_8) == JsonOutput.toJson(map)
    }

    @Feature('codec.jackson')
    def "Jackson encodes objects that are not Maps, collections or scalars"() {
        expect:
        new String(CodecRegistry.defaults().encode(new Annotated(id: 'c9'), MediaType.parse('application/json')), StandardCharsets.UTF_8) ==
                '{"cust_id":"c9"}'
    }

    @Feature('codec.jackson')
    def "a user ObjectMapper registered with jackson(...) wins over the auto-detected one"() {
        given:
        def mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        def reg = CodecRegistry.builder().jackson(mapper).build()

        expect:
        (reg.parse(utf8('{"id":"c1","name":"Ann","age":3,"address":{"city":"Hull"}}'), MediaType.parse('application/json'), Customer) as Customer).address.city == 'Hull'
        new String(reg.encode(new Address(city: 'York'), MediaType.parse('application/json')), StandardCharsets.UTF_8) == '{"city":"York"}'

        when:
        CodecRegistry.builder().jackson('not a mapper')

        then:
        thrown(IllegalArgumentException)
    }

    @Feature('codec.jackson')
    def "Jackson typed-result failures are ContentParseException"() {
        when:
        CodecRegistry.defaults().parse(utf8('{"id":"c1","shoeSize":9}'), MediaType.parse('application/json'), Customer)

        then:
        def e = thrown(ContentParseException)
        e.message.contains(Customer.name)
    }
}
