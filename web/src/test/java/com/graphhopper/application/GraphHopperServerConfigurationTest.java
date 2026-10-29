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

import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.http.ConfigurationFactory;
import com.graphhopper.http.GraphHopperBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the YAML configuration file: the graphhopper section binds to GraphHopperConfig, every other section
 * becomes flat web server properties, and -Ddw. system properties override single values.
 */
public class GraphHopperServerConfigurationTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void clearOverrides() {
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith(ConfigurationFactory.PROPERTY_PREFIX))
                .forEach(System::clearProperty);
    }

    private GraphHopperServerConfiguration read(String yaml) throws Exception {
        File file = tmp.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), yaml);
        return new ConfigurationFactory<>(GraphHopperServerConfiguration.class, GraphHopperBundle.newObjectMapper()).build(file);
    }

    @Test
    public void exampleConfigurationIsReadable() throws Exception {
        GraphHopperServerConfiguration config = new ConfigurationFactory<>(GraphHopperServerConfiguration.class,
                GraphHopperBundle.newObjectMapper()).build(new File("../config-example.yml"));
        GraphHopperConfig gh = config.getGraphHopperConfiguration();
        assertEquals("graph-cache", gh.getString("graph.location", ""));
        assertFalse(gh.getProfiles().isEmpty());
        Map<String, Object> server = config.getServerProperties();
        assertEquals("8989", server.get("server.port"));
        assertEquals("localhost", server.get("server.address"));
        assertEquals("8990", server.get("management.server.port"));
        assertEquals("localhost", server.get("management.server.address"));
    }

    @Test
    public void webServerSectionsBecomeFlatProperties() throws Exception {
        GraphHopperServerConfiguration config = read("""
                graphhopper:
                  graph.location: graph-cache
                server:
                  port: 1234
                  compression:
                    mime-types:
                      - application/json
                      - text/plain
                management:
                  server:
                    port: 1235
                logging:
                  level:
                    com.graphhopper: WARN
                """);
        assertEquals(Map.of("server.port", "1234",
                "server.compression.mime-types[0]", "application/json",
                "server.compression.mime-types[1]", "text/plain",
                "management.server.port", "1235",
                "logging.level.com.graphhopper", "WARN"), config.getServerProperties());
    }

    @Test
    public void graphHopperSectionKeepsDottedKeysAndProfiles() throws Exception {
        GraphHopperServerConfiguration config = read("""
                graphhopper:
                  datareader.file: map.osm.pbf
                  graph.location: graph-cache
                  prepare.min_network_size: 200
                  profiles:
                    - name: car
                      custom_model_files: [car.json]
                    - name: foot
                      custom_model:
                        speed:
                          - if: "true"
                            limit_to: 5
                """);
        GraphHopperConfig gh = config.getGraphHopperConfiguration();
        assertEquals("map.osm.pbf", gh.getString("datareader.file", ""));
        assertEquals(200, gh.getInt("prepare.min_network_size", 0));
        assertEquals(List.of("car", "foot"), gh.getProfiles().stream().map(Profile::getName).toList());
        assertNotNull(gh.getProfiles().get(1).getCustomModel());
    }

    @Test
    public void systemPropertiesOverrideScalarsAndLists() throws Exception {
        System.setProperty("dw.graphhopper.graph.location", "elsewhere");
        System.setProperty("dw.graphhopper.profiles[0].name", "truck");
        System.setProperty("dw.server.port", "4321");
        System.setProperty("dw.server.compression.mime-types", "a/b, c/d\\,e");
        GraphHopperServerConfiguration config = read("""
                graphhopper:
                  graph.location: graph-cache
                  profiles:
                    - name: car
                      custom_model_files: [car.json]
                server:
                  port: 1234
                  compression:
                    mime-types: [x/y]
                """);
        assertEquals("elsewhere", config.getGraphHopperConfiguration().getString("graph.location", ""));
        assertEquals("truck", config.getGraphHopperConfiguration().getProfiles().get(0).getName());
        assertEquals("4321", config.getServerProperties().get("server.port"));
        assertEquals("a/b", config.getServerProperties().get("server.compression.mime-types[0]"));
        assertEquals("c/d,e", config.getServerProperties().get("server.compression.mime-types[1]"));
    }

    @Test
    public void malformedYamlIsAConfigurationError() throws IOException {
        ConfigurationFactory.ConfigurationException e = assertThrows(ConfigurationFactory.ConfigurationException.class,
                () -> read("graphhopper: [unclosed\n"));
        assertTrue(e.getErrors().get(0).startsWith("Malformed YAML"), e.getErrors().toString());
    }

    @Test
    public void unknownGraphHopperPropertyTypeIsAConfigurationError() {
        assertThrows(ConfigurationFactory.ConfigurationException.class,
                () -> read("graphhopper:\n  profiles: 5\n"));
    }
}
