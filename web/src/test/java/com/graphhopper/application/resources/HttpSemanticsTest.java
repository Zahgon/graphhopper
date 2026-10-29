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
package com.graphhopper.application.resources;

import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.application.util.GraphHopperAppExtension;
import com.graphhopper.application.util.GraphHopperServerTestConfiguration;
import com.graphhopper.application.util.RawHttp;
import com.graphhopper.routing.TestProfiles;
import com.graphhopper.util.Helper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTTP behaviour of the application port that clients of the former Dropwizard server rely on: error envelopes,
 * method and path matching, content negotiation, CORS, request body parsing and response buffering.
 */
public class HttpSemanticsTest {
    private static final String DIR = "./target/http-semantics-gh/";
    private static final String ROUTE = "/route?point=42.554851,1.536198&point=42.510071,1.548128&profile=car";

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

    private static RawHttp app(String pathAndQuery) {
        return RawHttp.to(app.getLocalPort(), pathAndQuery);
    }

    @Test
    public void unknownPathAnswersWithJsonEnvelope() {
        RawHttp.Answer answer = app("/nope").send();
        assertEquals(404, answer.status());
        assertEquals("application/json", answer.header("Content-Type"));
        assertEquals("{\"code\":404,\"message\":\"HTTP 404 Not Found\"}", answer.text());
    }

    @Test
    public void optionsOnUnknownPathHasNoAllowHeader() {
        RawHttp.Answer answer = app("/nope").method("OPTIONS").send();
        assertEquals(404, answer.status());
        assertNull(answer.header("Allow"));
        assertEquals("{\"code\":404,\"message\":\"HTTP 404 Not Found\"}", answer.text());
    }

    @Test
    public void unsupportedMethodListsAllowedMethods() {
        RawHttp.Answer answer = app("/route").method("DELETE").send();
        assertEquals(405, answer.status());
        assertEquals("POST,GET,OPTIONS", answer.header("Allow"));
        assertEquals("{\"code\":405,\"message\":\"HTTP 405 Method Not Allowed\"}", answer.text());
    }

    @Test
    public void optionsOnResourceListsMethodsAsText() {
        RawHttp.Answer answer = app("/route").method("OPTIONS")
                .header("Origin", "http://example.org")
                .header("Access-Control-Request-Method", "POST").send();
        assertEquals(200, answer.status());
        assertEquals("HEAD,POST,GET,OPTIONS", answer.header("Allow"));
        assertEquals("text/plain", answer.header("Content-Type"));
        assertEquals("HEAD, POST, GET, OPTIONS", answer.text());
        assertEquals("*", answer.header("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "application/json|application/json",
            "application/*, text/*|application/*",
            "text/csv;q=0.9, application/json;q=0.9|text/csv;q=0.9",
            "application/json;Zed=1;q=0.50;a=\"x\"|application/json;a=x;q=0.50;zed=1",
    })
    public void optionsPreferringAnotherTypeAnswersWithoutEntity(String accept, String expected) {
        RawHttp.Answer answer = app("/route").method("OPTIONS").header("Accept", accept).send();
        assertEquals(200, answer.status());
        assertEquals("HEAD,POST,GET,OPTIONS", answer.header("Allow"));
        assertEquals(expected, answer.header("Content-Type"));
        assertEquals("", answer.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/csv, text/*", "image/png, text/plain", "*/*, image/*"})
    public void optionsAcceptingPlainTextListsMethodsAsText(String accept) {
        RawHttp.Answer answer = app("/route").method("OPTIONS").header("Accept", accept).send();
        assertEquals("text/plain", answer.header("Content-Type"));
        assertEquals("HEAD, POST, GET, OPTIONS", answer.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPTIONS", "DELETE", "PUT"})
    public void encodedFixedSegmentIsNotFoundForEveryMethod(String method) {
        RawHttp.Answer answer = app("/r%6Fute").method(method).header("Accept", "image/png").send();
        assertEquals(404, answer.status());
        assertNull(answer.header("Allow"));
        assertEquals("{\"code\":404,\"message\":\"HTTP 404 Not Found\"}", answer.text());
    }

    @Test
    public void missingAssetIsAnEmptyAnswerWithoutCors() {
        RawHttp.Answer answer = app("/maps/nope.js").header("Origin", "http://example.org").send();
        assertEquals(404, answer.status());
        assertNull(answer.header("Access-Control-Allow-Origin"));
        assertNull(answer.header("Vary"));
        assertEquals("", answer.text());
    }

    @Test
    public void matrixParametersAfterTrailingSlashAreIgnored() {
        RawHttp.Answer answer = app("/info/;jsessionid=1").send();
        assertEquals(200, answer.status());
        assertTrue(answer.text().contains("\"bbox\""));
    }

    @Test
    public void corsHeadersOnEveryAnswer() {
        for (String path : List.of("/health", "/nope", "/info", ROUTE)) {
            RawHttp.Answer answer = app(path).header("Origin", "http://example.org").send();
            assertEquals("*", answer.header("Access-Control-Allow-Origin"), path);
            assertEquals("GET, POST, HEAD, OPTIONS", answer.header("Access-Control-Allow-Methods"), path);
            assertEquals("Origin,Accept,X-Requested-With,Content-Type,Access-Control-Request-Method,"
                    + "Access-Control-Request-Headers,Range,GH-Client", answer.header("Access-Control-Allow-Headers"), path);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "text/plain|text/plain",
            "text/html|text/html",
            "application/*|application/octet-stream",
            "*/*|text/plain",
            "text/*;q=0.5, application/json|application/json",
    })
    public void healthEchoesTheAcceptedType(String accept, String expected) {
        RawHttp.Answer answer = app("/health").header("Accept", accept).send();
        assertEquals(200, answer.status());
        assertEquals(expected, answer.header("Content-Type"));
        assertEquals("OK", answer.text());
    }

    @Test
    public void healthWithUnsatisfiableWildcardIsNotAcceptable() {
        RawHttp.Answer answer = app("/health").header("Accept", "image/*").send();
        assertEquals(406, answer.status());
        assertEquals("{\"code\":406,\"message\":\"HTTP 406 Not Acceptable\"}", answer.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;;q=2", "application/json;q=1.5", "application/json, ,text/plain"})
    public void malformedAcceptIsABadRequest(String accept) {
        RawHttp.Answer answer = app(ROUTE).header("Accept", accept).send();
        assertEquals(400, answer.status());
    }

    @Test
    public void malformedAcceptOnUnknownPathIsStillNotFound() {
        RawHttp.Answer answer = app("/nope").header("Accept", "application/json;q=1.5").send();
        assertEquals(404, answer.status());
    }

    @Test
    public void unparsableNumberInQueryIsNotFound() {
        RawHttp.Answer answer = app(ROUTE + "&heading=1%20.5").send();
        assertEquals(404, answer.status());
        assertEquals("{\"code\":404,\"message\":\"HTTP 404 Not Found\"}", answer.text());
    }

    @Test
    public void trailingTokensInJsonBodyAreRejected() {
        RawHttp.Answer answer = app("/route").method("POST")
                .body("application/json", "{\"profile\":\"car\"} x").send();
        assertEquals(400, answer.status());
        assertEquals("{\"code\":400,\"message\":\"Unable to process JSON\"}", answer.text());
    }

    @Test
    public void unknownCharsetInJsonContentTypeIsIgnored() {
        RawHttp.Answer answer = app("/route").method("POST").body("application/json; charset=bogus",
                "{\"points\":[[1.536198,42.554851],[1.548128,42.510071]],\"profile\":\"car\"}").send();
        assertEquals(200, answer.status());
        assertTrue(answer.text().contains("\"paths\""));
    }

    @Test
    public void smallAnswersAreBufferedAndNotCompressed() {
        RawHttp.Answer answer = app("/health").header("Accept-Encoding", "gzip").send();
        assertEquals("2", answer.header("Content-Length"));
        assertNull(answer.header("Content-Encoding"));
        assertEquals("Accept-Encoding", answer.header("Vary"));
    }

    @Test
    public void largeAnswersAreCompressedWhenAccepted() {
        RawHttp.Answer answer = app(ROUTE + "&points_encoded=false&instructions=true").header("Accept-Encoding", "gzip").send();
        assertEquals(200, answer.status());
        assertEquals("gzip", answer.header("Content-Encoding"));
    }

    @Test
    public void gpxAnswerOfRoute() {
        RawHttp.Answer answer = app(ROUTE + "&type=gpx").send();
        assertEquals(200, answer.status());
        assertTrue(answer.header("Content-Type").startsWith("application/gpx+xml"), answer.header("Content-Type"));
        assertEquals("attachment;filename=GraphHopper.gpx", answer.header("Content-Disposition"));
        assertTrue(answer.text().contains("<trkpt"));
    }
}
