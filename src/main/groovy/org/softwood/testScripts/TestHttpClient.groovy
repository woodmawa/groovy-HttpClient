package org.softwood.testScripts

import org.softwood.http.GroovyHttpClient

def client = new GroovyHttpClient('https://api.restful-api.dev')

try {

    println "try calling url :  " + client.toString()
    // Asynchronous examples
    def future = client.get('/objects').thenAccept { response ->
        println "Async Response: $response"
    }

    // Synchronous examples
    def users = client.getSync('/objects')
    println "Sync Response: $users"

    // POST with body (sync)
    /*
    def newOrder = client.postSync('/orders', '{"productId": 123, "quantity": 5}') { ->
        header('Content-Type', 'application/json')
    }
    println "Order created: $newOrder"
    */
    // OPTIONS example (sync)
    def optionsResponse = client.optionsSync('/objects')
    def str = "allow"
    List perm = optionsResponse.getHeaders("allow")

    println "Allowed methods: ${perm}"

    // HEAD example (sync)
    def headResponse = client.headSync('/objects')
    def cntType = headResponse.getHeaders('Content-Type')
    println "Content-Type: ${cntType}"
} catch (Throwable t) {
    println "error thrown :" + t.toString()
    println t.stackTrace
}finally {

    client.close()
}