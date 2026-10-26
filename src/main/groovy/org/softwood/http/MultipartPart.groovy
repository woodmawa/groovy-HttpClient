package org.softwood.http

import groovy.transform.ToString

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A single multipart/form-data part.
 *
 * <p>{@code name}, {@code filename} and {@code contentType} end up inside part headers, so they are
 * validated on construction: CR, LF and NUL are rejected (they would start a new header or part), and
 * {@code "} is percent-encoded when written, per RFC 7578 section 4.2. Every construction path -- the
 * constructors, the static factories and the DSL {@link Builder} -- goes through the same check.</p>
 */
@ToString(includeNames = true, excludes = ['data'])
class MultipartPart {

    final String name
    final String filename
    final String contentType
    final byte[] data

    MultipartPart(String name, String filename, String contentType, byte[] data) {
        this.name = requireHeaderSafe('name', name, true)
        this.filename = requireHeaderSafe('filename', filename, false)
        this.contentType = requireHeaderSafe('contentType', contentType, false)
        this.data = data ?: new byte[0]
    }

    /** Map form, kept for 1.x callers: {@code new MultipartPart(name: 'file', filename: 'a.txt', ...)}. */
    MultipartPart(Map args) {
        this(args?.name as String, args?.filename as String, args?.contentType as String, args?.data as byte[])
    }

    /** Rejects CR, LF and NUL; a required value must also be non-empty. */
    static String requireHeaderSafe(String field, String value, boolean required) {
        if (value == null || value.isEmpty()) {
            if (required) throw new IllegalArgumentException("MultipartPart.${field} is required")
            return value
        }
        for (int i = 0; i < value.length(); i++) {
            int c = (int) value.charAt(i)
            if (c == 13 || c == 10 || c == 0) {
                throw new IllegalArgumentException("MultipartPart.${field} must not contain CR, LF or NUL characters")
            }
        }
        value
    }

    /** Value for a quoted-string in Content-Disposition: '"' percent-encoded (RFC 7578 section 4.2). */
    static String quote(String s) {
        s == null ? '' : s.replace('"', '%22')
    }

    // ---------------------------------------------------------------------
    // Static factory helpers
    // ---------------------------------------------------------------------

    /** A plain form field (no filename), as an HTML form sends it. 2.1: earlier versions sent {@code name.txt}. */
    static MultipartPart text(String name, String value) {
        new MultipartPart(
                name,
                null,
                "text/plain; charset=UTF-8",
                (value ?: "").getBytes("UTF-8")
        )
    }

    /** A JSON form field (no filename). 2.1: earlier versions sent {@code name.json}. */
    static MultipartPart json(String name, String json) {
        new MultipartPart(
                name,
                null,
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
         * sets text data: a plain form field unless a filename is also given (2.1).
         */
        void content(String text) {
            this.data = (text ?: "").getBytes("UTF-8")
            this.textContent = true
        }

        /** Binary content: an upload; without a filename it is named {@code <name>.bin}. */
        void data(byte[] d) {
            this.data = d ?: new byte[0]
            this.textContent = false
        }

        private boolean textContent = false

        MultipartPart build() {
            if (!name) {
                throw new IllegalArgumentException("MultipartPart.name is required")
            }
            new MultipartPart(name,
                    filename ?: (textContent ? null : "${name}.bin".toString()),
                    contentType ?: "application/octet-stream",
                    data ?: new byte[0])
        }
    }
}
