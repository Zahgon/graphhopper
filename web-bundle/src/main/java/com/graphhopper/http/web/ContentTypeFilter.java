/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.http.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/**
 * Requests to the resources with a Content-Type header that is not a media type (type/subtype followed by
 * ;name=value parameters) fail with a plain 400 error, whatever the method and resource. Parameters that are well-formed
 * but unusable, like "charset=bogus", are ignored.
 */
public class ContentTypeFilter extends OncePerRequestFilter {
    private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getHeader("Content-Type");
        if (contentType != null && !isMediaType(contentType)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String usable = contentType == null ? null : withoutUnusableParameters(contentType);
        if (usable != null && !usable.equals(contentType)) {
            chain.doFilter(new ContentTypeRequest(request, usable), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * @return the media type without the parameters that are syntactically fine but have no meaning here, like an
     * unknown charset
     */
    static String withoutUnusableParameters(String contentType) {
        MediaType type;
        try {
            MediaType.parseMediaType(contentType);
            return contentType;
        } catch (InvalidMediaTypeException e) {
            if (contentType.indexOf(';') < 0)
                return contentType;
        }
        try {
            type = MediaType.parseMediaType(contentType.substring(0, contentType.indexOf(';')));
        } catch (InvalidMediaTypeException e) {
            return contentType;
        }
        Map<String, String> usable = new LinkedHashMap<>();
        for (String parameter : contentType.substring(contentType.indexOf(';') + 1).split(";")) {
            int equals = parameter.indexOf('=');
            if (equals < 0)
                continue;
            String name = parameter.substring(0, equals).trim();
            String value = parameter.substring(equals + 1).trim();
            try {
                new MediaType(type, Map.of(name, value));
                usable.put(name, value);
            } catch (IllegalArgumentException e) {
                // dropped
            }
        }
        return new MediaType(type, usable).toString();
    }

    private static final class ContentTypeRequest extends HttpServletRequestWrapper {
        private final String contentType;

        ContentTypeRequest(HttpServletRequest request, String contentType) {
            super(request);
            this.contentType = contentType;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getCharacterEncoding() {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.getCharset() == null ? null : type.getCharset().name();
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) ? contentType : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) ? Collections.enumeration(List.of(contentType)) : super.getHeaders(name);
        }
    }

    static boolean isMediaType(String value) {
        Reader reader = new Reader(value);
        if (!reader.token() || !reader.consume('/') || !reader.token())
            return false;
        while (reader.consume(';')) {
            if (!reader.token() || !reader.consume('='))
                return false;
            if (!reader.quoted() && !reader.token())
                return false;
        }
        reader.skipWhitespace();
        return reader.atEnd();
    }

    private static final class Reader {
        private final String value;
        private int pos;

        Reader(String value) {
            this.value = value;
        }

        void skipWhitespace() {
            while (pos < value.length() && (value.charAt(pos) == ' ' || value.charAt(pos) == '\t'))
                pos++;
        }

        boolean atEnd() {
            return pos == value.length();
        }

        boolean consume(char c) {
            skipWhitespace();
            if (pos < value.length() && value.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        boolean token() {
            skipWhitespace();
            int start = pos;
            while (pos < value.length()) {
                char c = value.charAt(pos);
                if (c < 32 || c >= 127 || SEPARATORS.indexOf(c) >= 0)
                    break;
                pos++;
            }
            return pos > start;
        }

        boolean quoted() {
            skipWhitespace();
            if (pos >= value.length() || value.charAt(pos) != '"')
                return false;
            for (int i = pos + 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\')
                    i++;
                else if (c == '"') {
                    pos = i + 1;
                    return true;
                }
            }
            return false;
        }
    }
}
