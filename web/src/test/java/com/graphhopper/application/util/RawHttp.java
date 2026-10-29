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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends a request with any method, headers and body and keeps the answer as it came over the wire, for tests of
 * status codes, headers and content negotiation.
 */
public final class RawHttp {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final HttpRequest.Builder builder;
    private String method = "GET";
    private HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();

    private RawHttp(String url) {
        builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
    }

    public static RawHttp to(int port, String pathAndQuery) {
        return new RawHttp("http://localhost:" + port + pathAndQuery);
    }

    public RawHttp header(String name, String value) {
        builder.header(name, value);
        return this;
    }

    public RawHttp method(String method) {
        this.method = method;
        return this;
    }

    public RawHttp body(String contentType, String content) {
        builder.header("Content-Type", contentType);
        body = HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8);
        return this;
    }

    public Answer send() {
        try {
            HttpResponse<byte[]> response = HTTP.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Answer(response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static final class Answer {
        private final HttpResponse<byte[]> response;

        private Answer(HttpResponse<byte[]> response) {
            this.response = response;
        }

        public int status() {
            return response.statusCode();
        }

        /**
         * @return the header value or null; several values of the same header are joined with a comma
         */
        public String header(String name) {
            var values = response.headers().allValues(name);
            return values.isEmpty() ? null : String.join(",", values);
        }

        public Optional<String> contentType() {
            return response.headers().firstValue("Content-Type");
        }

        public byte[] bytes() {
            return response.body();
        }

        public String text() {
            return new String(response.body(), StandardCharsets.UTF_8);
        }
    }
}
