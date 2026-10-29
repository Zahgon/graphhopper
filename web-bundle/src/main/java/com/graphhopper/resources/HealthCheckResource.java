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
package com.graphhopper.resources;

import com.graphhopper.http.health.HealthCheck;
import com.graphhopper.http.health.HealthCheckRegistry;
import com.graphhopper.http.web.AcceptHeaderStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.SortedMap;

/**
 * This is a public facing health check that runs all registered health checks.
 * Instead of providing details like the admin health check, this only returns OK/200 or UNHEALTHY/500.
 * The text is written in the accepted media type, preferring text/plain, see {@link AcceptHeaderStrategy#select}.
 *
 * @author Robin Boldt
 */
@RestController
public class HealthCheckResource {

    private HealthCheckRegistry registry;

    public HealthCheckResource(HealthCheckRegistry registry) {
        this.registry = registry;
    }

    private static final List<MediaType> TEXT_TYPES = List.of(MediaType.TEXT_PLAIN, MediaType.ALL);

    @GetMapping("/health")
    public ResponseEntity<String> doGet(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        MediaType type = AcceptHeaderStrategy.select(AcceptHeaderStrategy.acceptableTypes(request), TEXT_TYPES);
        if (type == null)
            throw new HttpMediaTypeNotAcceptableException(TEXT_TYPES);
        SortedMap<String, HealthCheck.Result> results = registry.runHealthChecks();
        for (HealthCheck.Result result : results.values()) {
            if (!result.isHealthy()) {
                return ResponseEntity.status(500).contentType(type).body("UNHEALTHY");
            }
        }
        return ResponseEntity.ok().contentType(type).body("OK");
    }
}
