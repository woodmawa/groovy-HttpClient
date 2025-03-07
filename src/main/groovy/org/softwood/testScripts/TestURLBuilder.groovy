package testScripts

import org.softwood.url.URLBuilder


// Example 1: Using the builder with method chaining
def url1 = new URLBuilder()
        .protocol('https')
        .host('api.example.com')
        .port(8443)
        .basePath('/v1/resources')
        .param('query', 'search term')
        .param('page', 1)
        .param('tags', ['java', 'groovy', 'programming'])
        .build()

println "URL 1: $url1"

// Example 2: Using the constructor with a configuration closure
def url2 = new URLBuilder().with  {
    protocol 'http'
    host 'localhost'
    port 8080
    basePath 'api/users'
    param 'filter', 'active'
    param 'sort', 'name'
    params([limit: 50, offset: 0])
}

println "URL 2: $url2"

// Example 3: Using the build method with a configuration closure
def builder = new URLBuilder('api.myservice.com')
def url3 = builder.build {
    basePath '/v2/products'
    param 'categories', ['electronics', 'computers']
    param 'inStock', true
}

println "URL 3: $url3"

// Example 4: Single-line creation with closure
def url4 = new URLBuilder ({ host 'example.org'; basePath 'search'; param 'q', 'groovy' }).build()
println "URL 4: $url4"


// Example 2: Using the constructor with a configuration closure
def url5 = new URLBuilder().with  {
    protocol 'http'
    host 'localhost'
    port 8080
    basePath 'api/users'
    param 'id', 10..12  //declare using range
    //params([id: 10, id: 20])
}
println "URL 5: $url5"