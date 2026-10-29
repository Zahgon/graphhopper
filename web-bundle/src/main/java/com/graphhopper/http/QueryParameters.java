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
package com.graphhopper.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the parameters of a query string, in their order, with '+' standing for a space.
 */
public final class QueryParameters {

    private static final Logger LOG = LoggerFactory.getLogger(QueryParameters.class);

    private QueryParameters() {
    }

    public static Map<String, List<String>> parse(String queryString) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty())
            return result;
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty())
                continue;
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    /**
     * @return the query string without the parameters of the given names, null if nothing is left
     */
    public static String without(String queryString, String... names) {
        if (queryString == null)
            return null;
        List<String> kept = new ArrayList<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty())
                continue;
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            if (!List.of(names).contains(name))
                kept.add(pair);
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.debug("query parameter decode failed, keeping raw value: {}", e.toString());
            return value;
        }
    }
}
