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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.application.util.GraphHopperAppExtension;
import com.graphhopper.application.util.GraphHopperServerTestConfiguration;
import com.graphhopper.application.util.RawHttp;
import com.graphhopper.routing.TestProfiles;
import com.graphhopper.util.Helper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admin port: operational menu, ping, health checks, thread dump, metrics and tasks.
 */
public class AdminServerTest {
    private static final String DIR = "./target/admin-server-gh/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static final GraphHopperAppExtension app = new GraphHopperAppExtension(createConfig());

    private static GraphHopperServerConfiguration createConfig() {
        GraphHopperServerConfiguration config = new GraphHopperServerTestConfiguration();
        config.getGraphHopperConfiguration().
                putObject("datareader.file", "../core/files/andorra.osm.pbf").
                putObject("graph.location", DIR).
                putObject("import.osm.ignored_highways", "").
                setProfiles(List.of(TestProfiles.constantSpeed("car")));
        return config;
    }

    @BeforeAll
    @AfterAll
    public static void cleanUp() {
        Helper.removeDir(new File(DIR));
    }

    private static RawHttp admin(String path) {
        return RawHttp.to(app.getAdminPort(), path);
    }

    @Test
    public void adminRunsOnItsOwnPort() {
        assertNotEquals(app.getLocalPort(), app.getAdminPort());
        assertEquals(404, RawHttp.to(app.getLocalPort(), "/ping").send().status());
        assertEquals(404, admin("/route").send().status());
    }

    @Test
    public void ping() {
        RawHttp.Answer answer = admin("/ping").send();
        assertEquals(200, answer.status());
        assertEquals("text/plain;charset=iso-8859-1", answer.header("Content-Type"));
        assertEquals("must-revalidate,no-cache,no-store", answer.header("Cache-Control"));
        assertEquals("pong\n", answer.text());
    }

    @Test
    public void menuLinksTheServlets() {
        RawHttp.Answer answer = admin("/").send();
        assertEquals(200, answer.status());
        for (String link : List.of("metrics", "ping", "threads", "healthcheck"))
            assertTrue(answer.text().contains("href=\"/" + link), link);
    }

    @Test
    public void healthChecksReportEveryCheck() throws Exception {
        RawHttp.Answer answer = admin("/healthcheck").send();
        assertEquals(200, answer.status());
        assertEquals("application/json", answer.header("Content-Type"));
        JsonNode json = MAPPER.readTree(answer.bytes());
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("deadlocks", "graphhopper"), names);
        for (JsonNode check : json) {
            assertTrue(check.get("healthy").asBoolean());
            assertTrue(check.has("duration"));
            assertTrue(check.has("timestamp"));
        }
    }

    @Test
    public void metricsHaveTheRegistryLayout() throws Exception {
        RawHttp.Answer answer = admin("/metrics").send();
        assertEquals(200, answer.status());
        JsonNode json = MAPPER.readTree(answer.bytes());
        List<String> sections = new ArrayList<>();
        json.fieldNames().forEachRemaining(sections::add);
        assertEquals(List.of("version", "gauges", "counters", "histograms", "meters", "timers"), sections);
        assertEquals("4.0.0", json.get("version").asText());
        for (String section : sections.subList(1, sections.size()))
            assertTrue(json.get(section).isObject(), section);
    }

    @Test
    public void threadDumpIsText() {
        RawHttp.Answer answer = admin("/threads").send();
        assertEquals(200, answer.status());
        assertEquals("text/plain", answer.header("Content-Type"));
        assertTrue(answer.text().contains("main"));
    }

    @Test
    public void tasksRunOnPostOnly() {
        RawHttp.Answer gc = admin("/tasks/gc").method("POST").send();
        assertEquals(200, gc.status());
        assertEquals("Running GC...\nDone!\n", gc.text());
        assertEquals(405, admin("/tasks/gc").send().status());
        assertEquals(404, admin("/tasks/nope").method("POST").send().status());
    }

    @Test
    public void unknownTaskIsAnEmptyAnswerWithoutVary() {
        RawHttp.Answer answer = admin("/tasks/nope").header("Accept-Encoding", "gzip").send();
        assertEquals(404, answer.status());
        assertNull(answer.header("Vary"));
        assertNull(answer.header("Content-Type"));
        assertEquals("", answer.text());
    }

    @Test
    public void logLevelTask() {
        RawHttp.Answer answer = admin("/tasks/log-level?logger=com.graphhopper.application.AdminServerTest&level=WARN")
                .method("POST").send();
        assertEquals(200, answer.status());
        assertEquals("Configured logging level for com.graphhopper.application.AdminServerTest to WARN\n", answer.text());
    }

    @Test
    public void unknownAdminPathIsAnHtmlErrorPage() {
        RawHttp.Answer answer = admin("/nope").send();
        assertEquals(404, answer.status());
        assertEquals("text/html;charset=iso-8859-1", answer.header("Content-Type"));
        assertTrue(answer.text().contains("<h2>HTTP ERROR 404 Not Found</h2>"));
    }

    @Test
    public void noCorsOnAdminPort() {
        RawHttp.Answer answer = admin("/ping").header("Origin", "http://example.org").send();
        assertNull(answer.header("Access-Control-Allow-Origin"));
    }
}
