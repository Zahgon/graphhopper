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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * An HTTP resource of the server under test. Targets are immutable: {@link #queryParam} and {@link #path} return new
 * targets, so a common target can be extended in different ways.
 */
public final class ClientTarget {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String url;

    public ClientTarget(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    /**
     * @return a target with the query parameter added once for every value
     */
    public ClientTarget queryParam(String name, Object... values) {
        StringBuilder result = new StringBuilder(url);
        for (Object value : values) {
            result.append(result.indexOf("?") < 0 ? '?' : '&')
                    .append(encode(name)).append('=').append(encode(String.valueOf(value)));
        }
        return new ClientTarget(result.toString());
    }

    /**
     * @return a target with the path segment appended
     */
    public ClientTarget path(String segment) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String rest = query < 0 ? "" : url.substring(query);
        String separator = base.endsWith("/") || segment.startsWith("/") ? "" : "/";
        return new ClientTarget(base + separator + segment + rest);
    }

    /**
     * @param acceptedTypes the media types for the Accept header, none to send no Accept header
     */
    public Invocation request(String... acceptedTypes) {
        return new Invocation(acceptedTypes.length == 0 ? null : String.join(", ", acceptedTypes));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2C", ",");
    }

    /**
     * A request to the target that is about to be sent.
     */
    public final class Invocation {
        private final String accept;

        private Invocation(String accept) {
            this.accept = accept;
        }

        public ClientResponse get() {
            return send("GET", null);
        }

        /**
         * @return the body of a successful answer read as the given type
         * @throws IllegalStateException if the status is not 2xx
         */
        public <T> T get(Class<T> type) {
            return send("GET", null).readSuccessfulEntity(type);
        }

        public ClientResponse post(Entity entity) {
            return send("POST", entity);
        }

        /**
         * @return the body of a successful answer read as the given type
         * @throws IllegalStateException if the status is not 2xx
         */
        public <T> T post(Entity entity, Class<T> type) {
            return send("POST", entity).readSuccessfulEntity(type);
        }

        public Call buildGet() {
            return () -> send("GET", null);
        }

        public Call buildPost(Entity entity) {
            return () -> send("POST", entity);
        }

        private ClientResponse send(String method, Entity entity) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5));
            if (accept != null)
                builder.header("Accept", accept);
            if (entity == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", entity.getMediaType());
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(entity.getContent()));
            }
            try {
                HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                return new ClientResponse(response.statusCode(), response.headers(), response.body());
            } catch (IOException e) {
                throw new UncheckedIOException(method + " " + url + " failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(method + " " + url + " was interrupted", e);
            }
        }
    }

    /**
     * A prepared request.
     */
    public interface Call {
        ClientResponse invoke();
    }
}
