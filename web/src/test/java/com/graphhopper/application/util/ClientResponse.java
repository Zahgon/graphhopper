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
package com.graphhopper.application.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.jackson.Jackson;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;

/**
 * The answer of the server under test. Bodies are read as String, byte[], InputStream or, with the JSON mapper of
 * GraphHopper ignoring unknown properties, as any other type.
 */
public final class ClientResponse implements AutoCloseable {
    private static final ObjectMapper MAPPER = Jackson.newObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;

    ClientResponse(int status, HttpHeaders headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    /**
     * @return the value of the header, several values joined with commas, null if absent
     */
    public String getHeaderString(String name) {
        var values = headers.allValues(name);
        return values.isEmpty() ? null : String.join(",", values);
    }

    /**
     * @return the Content-Type, null if absent
     */
    public String getMediaType() {
        return getHeaderString("Content-Type");
    }

    @SuppressWarnings("unchecked")
    public <T> T readEntity(Class<T> type) {
        if (type == String.class)
            return (T) new String(body, StandardCharsets.UTF_8);
        if (type == byte[].class)
            return (T) body;
        if (type == InputStream.class)
            return (T) new ByteArrayInputStream(body);
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the body as " + type.getName() + ": "
                    + new String(body, StandardCharsets.UTF_8), e);
        }
    }

    <T> T readSuccessfulEntity(Class<T> type) {
        if (status < 200 || status >= 300)
            throw new IllegalStateException("HTTP " + status + ": " + new String(body, StandardCharsets.UTF_8));
        return readEntity(type);
    }

    @Override
    public void close() {
    }
}
