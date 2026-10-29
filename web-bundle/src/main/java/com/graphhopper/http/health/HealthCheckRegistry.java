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
package com.graphhopper.http.health;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The named health checks of the application, run by the public /health resource and the admin /healthcheck page.
 */
public class HealthCheckRegistry {
    private final ConcurrentMap<String, HealthCheck> healthChecks = new ConcurrentHashMap<>();

    public void register(String name, HealthCheck healthCheck) {
        if (healthChecks.putIfAbsent(name, healthCheck) != null)
            throw new IllegalArgumentException("A health check named " + name + " already exists");
    }

    public void unregister(String name) {
        healthChecks.remove(name);
    }

    public SortedMap<String, HealthCheck> getHealthChecks() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(healthChecks));
    }

    /**
     * @return the results of all checks, sorted by name
     */
    public SortedMap<String, HealthCheck.Result> runHealthChecks() {
        SortedMap<String, HealthCheck.Result> results = new TreeMap<>();
        for (var entry : getHealthChecks().entrySet())
            results.put(entry.getKey(), entry.getValue().execute());
        return Collections.unmodifiableSortedMap(results);
    }
}
