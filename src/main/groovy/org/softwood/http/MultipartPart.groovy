package org.softwood.http

/**
 * Represents a single part in a multipart/form-data request.
 */
import groovy.transform.MapConstructor
import groovy.transform.ToString

/**
 * Represents a single multipart/form-data part.
 */
@MapConstructor
@ToString(includeNames = true)
class MultipartPart {

    final String name
    final String filename
    final String contentType
    final byte[] data

    MultipartPart(String name, String filename, String contentType, byte[] data) {
        this.name = name
        this.filename = filename
        this.contentType = contentType
        this.data = data
    }

    // ---------------------------------------------------------------------
    // Static factory helpers used in tests
    // ---------------------------------------------------------------------

    static MultipartPart text(String name, String value) {
        new MultipartPart(
                name,
                "${name}.txt",
                "text/plain; charset=UTF-8",
                (value ?: "").getBytes("UTF-8")
        )
    }

    static MultipartPart json(String name, String json) {
        new MultipartPart(
                name,
                "${name}.json",
                "application/json; charset=UTF-8",
                (json ?: "").getBytes("UTF-8")
        )
    }

    static MultipartPart bytes(String name, String filename, String contentType, byte[] data) {
        new MultipartPart(
                name,
                filename,
                contentType ?: "application/octet-stream",
                data ?: new byte[0]
        )
    }

    // ---------------------------------------------------------------------
    // DSL Builder used by GroovyHttpClient.MultipartDSLBuilder
    // ---------------------------------------------------------------------

    static class Builder {
        String name
        String filename
        String contentType = "text/plain; charset=UTF-8"
        byte[] data = new byte[0]

        void name(String n)          { this.name = n }
        void filename(String f)      { this.filename = f }
        void contentType(String c)   { this.contentType = c }

        /**
         * DSL method:
         *   content "Hello World"
         * sets data bytes and leaves filename if already set.
         */
        void content(String text) {
            this.data = (text ?: "").getBytes("UTF-8")
            if (!this.filename) {
                this.filename = "${name ?: 'part'}.txt"
            }
        }

        void data(byte[] d) {
            this.data = d ?: new byte[0]
        }

        MultipartPart build() {
            if (!name) {
                throw new IllegalArgumentException("MultipartPart.name is required")
            }
            if (!filename) {
                filename = "${name}.bin"
            }
            if (!contentType) {
                contentType = "application/octet-stream"
            }
            if (data == null) {
                data = new byte[0]
            }
            new MultipartPart(name, filename, contentType, data)
        }
    }
}