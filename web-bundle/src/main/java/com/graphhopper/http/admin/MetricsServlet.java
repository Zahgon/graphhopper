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
package com.graphhopper.http.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Writes all meters of the registry as JSON, grouped into the sections gauges, counters, histograms, meters and
 * timers.
 */
class MetricsServlet extends HttpServlet {
    static final String VERSION = "4.0.0";

    private final MeterRegistry registry;
    private final ObjectMapper mapper;

    MetricsServlet(MeterRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", AdminServlet.CACHE_CONTROL);
        resp.setStatus(HttpServletResponse.SC_OK);
        try (OutputStream output = resp.getOutputStream()) {
            HealthCheckServlet.writer(mapper, req).writeValue(output, snapshot());
        }
    }

    Map<String, Object> snapshot() {
        Map<String, Object> gauges = new TreeMap<>();
        Map<String, Object> counters = new TreeMap<>();
        Map<String, Object> histograms = new TreeMap<>();
        Map<String, Object> meters = new TreeMap<>();
        Map<String, Object> timers = new TreeMap<>();
        for (Meter meter : registry.getMeters()) {
            String name = name(meter.getId());
            meter.match(
                    gauge -> gauges.put(name, Map.of("value", gauge.value())),
                    counter -> counters.put(name, Map.of("count", (long) counter.count())),
                    timer -> timers.put(name, timer(timer.count(), timer.totalTime(TimeUnit.MILLISECONDS), timer.max(TimeUnit.MILLISECONDS))),
                    summary -> histograms.put(name, histogram(summary)),
                    longTaskTimer -> timers.put(name, timer(longTaskTimer.activeTasks(), longTaskTimer.duration(TimeUnit.MILLISECONDS), longTaskTimer.max(TimeUnit.MILLISECONDS))),
                    timeGauge -> gauges.put(name, Map.of("value", timeGauge.value(TimeUnit.MILLISECONDS))),
                    functionCounter -> counters.put(name, Map.of("count", (long) functionCounter.count())),
                    functionTimer -> timers.put(name, timer((long) functionTimer.count(), functionTimer.totalTime(TimeUnit.MILLISECONDS), functionTimer.mean(TimeUnit.MILLISECONDS))),
                    other -> meters.put(name, measurements(other)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("gauges", gauges);
        result.put("counters", counters);
        result.put("histograms", histograms);
        result.put("meters", meters);
        result.put("timers", timers);
        return result;
    }

    private static String name(Meter.Id id) {
        if (id.getTags().isEmpty())
            return id.getName();
        return id.getName() + id.getTags().stream().map(tag -> tag.getKey() + "=" + tag.getValue()).collect(Collectors.joining(",", "{", "}"));
    }

    private static Map<String, Object> timer(long count, double totalMillis, double maxMillis) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", count);
        values.put("max", maxMillis);
        values.put("mean", count == 0 ? 0.0 : totalMillis / count);
        values.put("total", totalMillis);
        values.put("duration_units", "milliseconds");
        return values;
    }

    private static Map<String, Object> histogram(DistributionSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", summary.count());
        values.put("max", summary.max());
        values.put("mean", summary.mean());
        values.put("total", summary.totalAmount());
        return values;
    }

    private static Map<String, Object> measurements(Meter meter) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Measurement measurement : meter.measure())
            values.put(measurement.getStatistic().getTagValueRepresentation(), measurement.getValue());
        return values;
    }
}
