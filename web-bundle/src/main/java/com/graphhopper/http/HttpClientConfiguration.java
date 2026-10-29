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

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of the HTTP client that fetches the GTFS realtime feeds. Durations are written like "500ms", "5s" or "1m".
 */
public class HttpClientConfiguration {
    private static final Pattern DURATION = Pattern.compile("\\s*(\\d+)\\s*(ns|us|ms|s|m|h|d|nanoseconds?|microseconds?|milliseconds?|seconds?|minutes?|hours?|days?)\\s*");

    @JsonProperty
    private String timeout = "500ms";

    @JsonProperty
    private String connectionTimeout = "500ms";

    @JsonProperty
    private String connectionRequestTimeout = "500ms";

    @JsonProperty
    private int maxConnections = 1024;

    @JsonProperty
    private int maxConnectionsPerRoute = 1024;

    @JsonProperty
    private String userAgent = "graphhopper-gtfs-realtime";

    public Duration getTimeout() {
        return parseDuration(timeout);
    }

    public Duration getConnectionTimeout() {
        return parseDuration(connectionTimeout);
    }

    public Duration getConnectionRequestTimeout() {
        return parseDuration(connectionRequestTimeout);
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getMaxConnectionsPerRoute() {
        return maxConnectionsPerRoute;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public CloseableHttpClient build() {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(getConnectionTimeout()))
                .setSocketTimeout(Timeout.of(getTimeout()))
                .build();
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(maxConnections)
                        .setMaxConnPerRoute(maxConnectionsPerRoute)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(getConnectionRequestTimeout()))
                        .build())
                .setUserAgent(userAgent)
                .build();
    }

    static Duration parseDuration(String value) {
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches())
            throw new IllegalArgumentException("Invalid duration: " + value);
        long count = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        if (unit.startsWith("nano") || unit.equals("ns"))
            return Duration.ofNanos(count);
        if (unit.startsWith("micro") || unit.equals("us"))
            return Duration.ofNanos(count * 1000);
        if (unit.startsWith("milli") || unit.equals("ms"))
            return Duration.ofMillis(count);
        if (unit.startsWith("s"))
            return Duration.ofSeconds(count);
        if (unit.startsWith("m"))
            return Duration.ofMinutes(count);
        if (unit.startsWith("h"))
            return Duration.ofHours(count);
        return Duration.ofDays(count);
    }
}
