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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.*;

/**
 * Reads the Accept header with the grammar of JAX-RS:
 * <ul>
 *     <li>media ranges are separated by commas, a trailing comma is allowed, an empty range in between is not</li>
 *     <li>a range without subtype like "bogus" stands for "bogus/*", but "application/" or "/json" are malformed</li>
 *     <li>whitespace is allowed around ranges and before and after ';', but not around '/' and '='</li>
 *     <li>parameters are name=token or name="quoted string", ";;" and a trailing ';' are allowed, a later parameter
 *     with the same name replaces an earlier one</li>
 *     <li>the quality q is "0" or "1" optionally followed by '.' and digits, or '.' and digits, at most five
 *     characters long, and not more than 1</li>
 * </ul>
 * A malformed header does not fail content negotiation itself: the request is answered with a plain 400 error once a
 * resource method has been found for it, see {@link GHRequestMappingHandlerMapping}.
 */
public class AcceptHeaderStrategy implements ContentNegotiationStrategy {

    private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";
    private static final int DEFAULT_QUALITY = 1000;

    /**
     * An Accept header that does not follow the grammar.
     */
    public static class MalformedAcceptHeaderException extends RuntimeException {
        public MalformedAcceptHeaderException(String message) {
            super(message);
        }
    }

    /**
     * A media range of the Accept header with its quality in thousandths and its spelling as JAX-RS writes it back:
     * type and parameter names in lower case, parameters sorted by name, the quality as sent.
     */
    public record AcceptableType(MediaType mediaType, int quality, String spelling) {
        public AcceptableType(MediaType mediaType, int quality) {
            this(mediaType, quality, mediaType.toString());
        }
    }

    @Override
    public List<MediaType> resolveMediaTypes(NativeWebRequest request) {
        String header = joined(request.getHeaderValues(HttpHeaders.ACCEPT));
        List<MediaType> result;
        try {
            result = header == null ? List.of() : parse(header);
        } catch (MalformedAcceptHeaderException e) {
            return MEDIA_TYPE_ALL_LIST;
        }
        if (result.isEmpty())
            return MEDIA_TYPE_ALL_LIST;
        result = new ArrayList<>(result);
        MimeTypeUtils.sortBySpecificity(result);
        return result;
    }

    /**
     * @return true if the request has an Accept header that cannot be parsed
     */
    public static boolean isMalformed(HttpServletRequest request) {
        String header = joined(request.getHeaders(HttpHeaders.ACCEPT));
        if (header == null)
            return false;
        try {
            parseAcceptable(header);
            return false;
        } catch (MalformedAcceptHeaderException e) {
            return true;
        }
    }

    /**
     * @return the media ranges of the Accept header of the request sorted like JAX-RS does: by quality, and for the
     * same quality concrete types before "type/*" before "*&#47;*", otherwise in the order of the header. An absent
     * or empty header accepts everything.
     */
    public static List<AcceptableType> acceptableTypes(HttpServletRequest request) {
        String header = joined(request.getHeaders(HttpHeaders.ACCEPT));
        List<AcceptableType> result = new ArrayList<>(header == null ? List.of() : parseAcceptable(header));
        if (result.isEmpty())
            result.add(new AcceptableType(MediaType.ALL, DEFAULT_QUALITY));
        result.sort(Comparator.comparingInt((AcceptableType type) -> -type.quality())
                .thenComparingInt(type -> wildcards(type.mediaType())));
        return result;
    }

    /**
     * @return the media ranges of the header, with their quality as "q" parameter
     * @throws MalformedAcceptHeaderException if the header does not follow the grammar
     */
    public static List<MediaType> parse(String header) {
        List<MediaType> result = new ArrayList<>();
        for (AcceptableType type : parseAcceptable(header)) {
            if (type.quality() == DEFAULT_QUALITY) {
                result.add(type.mediaType());
            } else {
                Map<String, String> parameters = new LinkedHashMap<>(type.mediaType().getParameters());
                parameters.put("q", String.valueOf(Math.max(0, type.quality()) / 1000.0));
                result.add(new MediaType(type.mediaType(), parameters));
            }
        }
        return result;
    }

    static List<AcceptableType> parseAcceptable(String header) {
        Reader reader = new Reader(header);
        List<AcceptableType> result = new ArrayList<>();
        while (reader.hasNext()) {
            result.add(readMediaRange(reader));
            if (reader.hasNext())
                reader.nextSeparator(',');
        }
        return result;
    }

    private static AcceptableType readMediaRange(Reader reader) {
        reader.hasNext();
        String type = reader.nextToken();
        String subtype = "*";
        if (reader.hasNextSeparator('/', false)) {
            reader.nextSeparator('/');
            subtype = reader.nextToken();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        while (reader.hasNextInElement()) {
            reader.nextSeparator(';');
            while (reader.hasNextSeparator(';', true))
                reader.nextSeparator(';');
            if (!reader.hasNextInElement())
                break;
            String name = reader.nextToken().toLowerCase(Locale.ROOT);
            reader.nextSeparator('=');
            parameters.put(name, reader.nextTokenOrQuotedString());
        }
        String spelling = spelling(type, subtype, parameters);
        int quality = DEFAULT_QUALITY;
        String q = parameters.remove("q");
        if (q != null)
            quality = readQuality(q.startsWith("\"") ? q.substring(1, q.length() - 1).replaceAll("\\\\(.)", "$1") : q);
        Map<String, String> valid = new LinkedHashMap<>();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            try {
                new MediaType(type, subtype, Map.of(parameter.getKey(), parameter.getValue()));
                valid.put(parameter.getKey(), parameter.getValue());
            } catch (IllegalArgumentException e) {
                // a parameter like charset=bogus is kept by the client but cannot be represented
            }
        }
        return new AcceptableType(new MediaType(type, subtype, valid), quality, spelling);
    }

    private static String spelling(String type, String subtype, Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(type.toLowerCase(Locale.ROOT)).append('/').append(subtype.toLowerCase(Locale.ROOT));
        for (Map.Entry<String, String> parameter : new TreeMap<>(parameters).entrySet()) {
            String value = parameter.getValue();
            if (value.startsWith("\"")) {
                String unquoted = value.substring(1, value.length() - 1).replaceAll("\\\\(.)", "$1");
                boolean token = !unquoted.isEmpty() && unquoted.chars().allMatch(c -> Reader.isTokenChar((char) c));
                value = token ? unquoted : '"' + unquoted.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
            result.append(';').append(parameter.getKey()).append('=').append(value);
        }
        return result.toString();
    }

    private static int readQuality(String q) {
        if (q.isEmpty() || q.length() > 5)
            throw new MalformedAcceptHeaderException("Invalid quality value " + q);
        int index = 0;
        char whole = q.charAt(index++);
        char c = whole;
        if (c == '0' || c == '1') {
            if (index == q.length())
                return (c - '0') * 1000;
            c = q.charAt(index++);
            if (c != '.')
                throw new MalformedAcceptHeaderException("Invalid quality value " + q);
            if (index == q.length())
                return (c - '0') * 1000;
        } else if (c == '.') {
            if (index == q.length())
                throw new MalformedAcceptHeaderException("Invalid quality value " + q);
        } else {
            throw new MalformedAcceptHeaderException("Invalid quality value " + q);
        }
        int value = 0;
        int exponent = 100;
        while (index < q.length()) {
            c = q.charAt(index++);
            if (c < '0' || c > '9')
                throw new MalformedAcceptHeaderException("Invalid quality value " + q);
            value += (c - '0') * exponent;
            exponent /= 10;
        }
        if (whole == '1') {
            if (value > 0)
                throw new MalformedAcceptHeaderException("Quality value " + q + " is greater than 1");
            return DEFAULT_QUALITY;
        }
        return value;
    }

    /**
     * Selects the media type of a response like JAX-RS: every accepted range is combined with every media type the
     * writers of the entity declare, the most specific combination wins, then the one with the higher quality, then
     * the one where fewer wildcards had to be matched; on a tie the earlier one. "*&#47;*" and "application/*" become
     * application/octet-stream.
     *
     * @return the selected media type, null if no writer can produce an accepted type
     */
    public static MediaType select(List<AcceptableType> accepted, List<MediaType> writerTypes) {
        MediaType selected = combine(accepted, writerTypes, null);
        if (selected == null || selected.isConcrete())
            return selected;
        if (selected.isWildcardType() || selected.getType().equals("application"))
            return MediaType.APPLICATION_OCTET_STREAM;
        return null;
    }

    /**
     * @return the index of the writer media type that {@link #select} combines with an accepted range, -1 if none
     */
    public static int selectWriter(List<AcceptableType> accepted, List<MediaType> writerTypes) {
        int[] writerIndex = {-1};
        combine(accepted, writerTypes, writerIndex);
        return writerIndex[0];
    }

    private static MediaType combine(List<AcceptableType> accepted, List<MediaType> writerTypes, int[] writerIndex) {
        MediaType selected = null;
        int selectedQuality = 0;
        int selectedDistance = 0;
        for (AcceptableType acceptable : accepted) {
            MediaType accept = acceptable.mediaType();
            for (int index = 0; index < writerTypes.size(); index++) {
                MediaType writer = writerTypes.get(index);
                if (!writer.isCompatibleWith(accept))
                    continue;
                MediaType combined = mostSpecific(accept, writer);
                int distance = (accept.isWildcardType() != writer.isWildcardType() ? 1 : 0)
                        + (accept.isWildcardSubtype() != writer.isWildcardSubtype() ? 1 : 0);
                int order = selected == null ? -1 : wildcards(combined) - wildcards(selected);
                if (order == 0)
                    order = selectedQuality - acceptable.quality();
                if (order == 0)
                    order = distance - selectedDistance;
                if (order < 0) {
                    selected = combined;
                    selectedQuality = acceptable.quality();
                    selectedDistance = distance;
                    if (writerIndex != null)
                        writerIndex[0] = index;
                }
            }
        }
        return selected;
    }

    private static MediaType mostSpecific(MediaType accept, MediaType writer) {
        if (writer.isWildcardType() || (writer.isWildcardSubtype() && !accept.isWildcardType()))
            return accept;
        return writer;
    }

    private static int wildcards(MediaType type) {
        return type.isWildcardType() ? 2 : type.isWildcardSubtype() ? 1 : 0;
    }

    private static String joined(String[] values) {
        return values == null || values.length == 0 ? null : String.join(",", values);
    }

    private static String joined(Enumeration<String> values) {
        if (values == null || !values.hasMoreElements())
            return null;
        return String.join(",", Collections.list(values));
    }

    private static final class Reader {
        private final String value;
        private int pos;

        Reader(String value) {
            this.value = value;
        }

        boolean hasNext() {
            skipWhitespace();
            return pos < value.length();
        }

        /**
         * @return true if the current media range continues, i.e. the next character is neither the end nor a comma
         */
        boolean hasNextInElement() {
            skipWhitespace();
            return pos < value.length() && value.charAt(pos) != ',';
        }

        boolean hasNextSeparator(char c, boolean skipWhitespace) {
            if (skipWhitespace)
                skipWhitespace();
            return pos < value.length() && value.charAt(pos) == c;
        }

        void nextSeparator(char c) {
            check();
            if (value.charAt(pos) != c)
                throw new MalformedAcceptHeaderException("Expected '" + c + "' at " + pos + " in " + value);
            pos++;
        }

        String nextToken() {
            check();
            int start = pos;
            while (pos < value.length() && isTokenChar(value.charAt(pos)))
                pos++;
            if (pos == start)
                throw new MalformedAcceptHeaderException("Expected a token at " + pos + " in " + value);
            return value.substring(start, pos);
        }

        /**
         * @return the token, or the quoted string including its quotes
         */
        String nextTokenOrQuotedString() {
            check();
            if (value.charAt(pos) != '"')
                return nextToken();
            for (int i = pos + 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    String result = value.substring(pos, i + 1);
                    pos = i + 1;
                    return result;
                }
            }
            throw new MalformedAcceptHeaderException("Unbalanced quoted string in " + value);
        }

        private void check() {
            if (pos >= value.length())
                throw new MalformedAcceptHeaderException("Unexpected end of " + value);
            if (value.charAt(pos) > 127)
                throw new MalformedAcceptHeaderException("Invalid character in " + value);
        }

        private void skipWhitespace() {
            while (pos < value.length() && (value.charAt(pos) == ' ' || value.charAt(pos) == '\t'))
                pos++;
        }

        private static boolean isTokenChar(char c) {
            return c > 0x20 && c < 0x7f && SEPARATORS.indexOf(c) < 0;
        }
    }
}
