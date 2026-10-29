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
import com.fasterxml.jackson.databind.ObjectWriter;
import com.graphhopper.http.health.HealthCheck;
import com.graphhopper.http.health.HealthCheckRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.SortedMap;

/**
 * Runs all health checks and writes their results as JSON: 200 if all are healthy, 500 if one is not and 501 if
 * there are none.
 */
class HealthCheckServlet extends HttpServlet {
    private final HealthCheckRegistry registry;
    private final ObjectMapper mapper;

    HealthCheckServlet(HealthCheckRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SortedMap<String, HealthCheck.Result> results = registry.runHealthChecks();
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", AdminServlet.CACHE_CONTROL);
        if (results.isEmpty())
            resp.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
        else if (results.values().stream().allMatch(HealthCheck.Result::isHealthy))
            resp.setStatus(HttpServletResponse.SC_OK);
        else
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        try (OutputStream output = resp.getOutputStream()) {
            writer(mapper, req).writeValue(output, results);
        }
    }

    static ObjectWriter writer(ObjectMapper mapper, HttpServletRequest req) {
        return Boolean.parseBoolean(req.getParameter("pretty")) ? mapper.writerWithDefaultPrettyPrinter() : mapper.writer();
    }
}
