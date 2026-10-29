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
package com.graphhopper.application;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.http.GraphHopperBundleConfiguration;
import com.graphhopper.http.RealtimeConfiguration;

import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The configuration file of the server. The graphhopper and gtfs_realtime sections configure GraphHopper, all other
 * sections are settings of the web server, like server.port, management.server.port or logging.level.root.
 */
public class GraphHopperServerConfiguration implements GraphHopperBundleConfiguration {

    @NotNull
    @JsonProperty
    private final GraphHopperConfig graphhopper = new GraphHopperConfig();

    @JsonProperty
    private final RealtimeConfiguration gtfsRealtime = new RealtimeConfiguration();

    private final Map<String, Object> serverProperties = new LinkedHashMap<>();

    public GraphHopperServerConfiguration() {
    }

    @Override
    public GraphHopperConfig getGraphHopperConfiguration() {
        return graphhopper;
    }

    @Override
    public RealtimeConfiguration gtfsrealtime() {
        return gtfsRealtime;
    }

    @JsonAnySetter
    public void setServerProperty(String name, JsonNode value) {
        flatten(name, value, serverProperties);
    }

    /**
     * @return the settings of the web server, with flat names like "server.port"
     */
    public Map<String, Object> getServerProperties() {
        return serverProperties;
    }

    private static void flatten(String name, JsonNode value, Map<String, Object> result) {
        if (value.isObject()) {
            value.properties().forEach(e -> flatten(name + "." + e.getKey(), e.getValue(), result));
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++)
                flatten(name + "[" + i + "]", value.get(i), result);
        } else if (!value.isNull()) {
            result.put(name, value.asText());
        }
    }
}
