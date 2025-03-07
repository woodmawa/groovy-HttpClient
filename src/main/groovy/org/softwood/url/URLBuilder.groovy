package org.softwood.url

/**
 * URLBuilder - A fluent builder for constructing URLs with encoded query parameters
 * Supports both single values and arrays of values for query parameters
 */

class URLBuilder {
    private String protocol = 'https'
    private String host
    private Integer port
    private String basePath = ''
    private Map<String, Object> params = [:]

    /**
     * Default constructor
     */
    URLBuilder() {}

    /**
     * Constructor with host initialization
     * @param host The host name
     */
    URLBuilder(String host) {
        this.host = host
    }

    /**
     * Constructor that accepts a configuration closure
     * @param configureClosure Closure for configuring the builder
     */
    URLBuilder(@DelegatesTo(URLBuilder) Closure configureClosure) {
        configureClosure.delegate = this
        configureClosure.resolveStrategy = Closure.DELEGATE_FIRST
        configureClosure()
    }

    /**
     * Sets the protocol (http or https)
     * @param protocol The protocol to use
     * @return this builder instance for method chaining
     */
    URLBuilder protocol(String protocol) {
        this.protocol = protocol
        return this
    }

    /**
     * Sets the host
     * @param host The host name
     * @return this builder instance for method chaining
     */
    URLBuilder host(String host) {
        this.host = host
        return this
    }

    /**
     * Sets the port
     * @param port The port number
     * @return this builder instance for method chaining
     */
    URLBuilder port(Integer port) {
        this.port = port
        return this
    }

    /**
     * Sets the base path
     * @param basePath The base path segment of the URL
     * @return this builder instance for method chaining
     */
    URLBuilder basePath(String basePath) {
        this.basePath = basePath
        return this
    }

    /**
     * Adds a single parameter to the URL
     * @param name The parameter name
     * @param value The parameter value
     * @return this builder instance for method chaining
     */
    URLBuilder param(String name, Object value) {
        this.params[name] = value
        return this
    }

    /**
     * Adds multiple parameters to the URL
     * @param paramsMap Map of parameters to add
     * @return this builder instance for method chaining
     */
    URLBuilder params(Map<String, Object> paramsMap) {
        this.params.putAll(paramsMap)
        return this
    }

    /**
     * Builds the query parameter portion of the URL
     * @return URL-encoded query string
     */
    private String buildQueryString() {
        if (params.isEmpty()) {
            return ''
        }

        return params.collect { name, value ->
            if (value instanceof Collection || value instanceof Range|| value instanceof Object[]) {
                return value.collect { val ->
                    "${URLEncoder.encode(name, 'UTF-8')}=${URLEncoder.encode(val.toString(), 'UTF-8')}"
                }.join('&')
            } else {
                "${URLEncoder.encode(name, 'UTF-8')}=${URLEncoder.encode(value.toString(), 'UTF-8')}"
            }
        }.join('&')
    }

    /**
     * Validates builder state and throws exception if essential components are missing
     */
    private void validate() {
        if (!host) {
            throw new IllegalStateException("Host is required to build a URL")
        }
    }

    /**
     * Builds the complete URL
     * @return The complete, well-formed URL as a String
     */
    String build() {
        validate()

        StringBuilder url = new StringBuilder()
        url.append(protocol).append('://')
        url.append(host)

        if (port) {
            url.append(':').append(port)
        }

        // Handle base path - ensure it starts with a slash if not empty
        if (basePath) {
            if (!basePath.startsWith('/')) {
                url.append('/')
            }
            url.append(basePath)
        }

        // Add query parameters if any
        String queryString = buildQueryString()
        if (queryString) {
            url.append('?').append(queryString)
        }

        return url.toString()
    }

    /**
     * Builds the URL with a configuration closure
     * @param configureClosure Closure for last-minute configuration
     * @return The complete URL
     */
    String build(@DelegatesTo(URLBuilder) Closure configureClosure) {
        configureClosure.delegate = this
        configureClosure.resolveStrategy = Closure.DELEGATE_FIRST
        configureClosure()
        return build()
    }

    /**
     * String representation of the builder (same as build())
     * @return The complete URL
     */
    @Override
    String toString() {
        return build()
    }
}
