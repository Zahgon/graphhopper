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

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gpx.GpxConversions;
import com.graphhopper.http.AbstractParam;
import com.graphhopper.http.GHPointParam;
import com.graphhopper.http.GHRequestTransformer;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.http.QueryParameters;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.Parameters.Details.PATH_DETAILS;
import static com.graphhopper.util.Parameters.Routing.*;
import static java.util.stream.Collectors.toList;

/**
 * Resource to use GraphHopper in a remote client application like mobile or browser. Note: If type
 * is json it returns the points in GeoJson array format [longitude,latitude] unlike the format "lat,lon"
 * used for the request. See the full API response format in docs/web/api-doc.md
 *
 * @author Peter Karich
 */
@RestController
@RequestMapping("/route")
public class RouteResource {

    private static final MediaType GPX = MediaType.parseMediaType("application/gpx+xml");

    private static final Logger logger = LoggerFactory.getLogger(RouteResource.class);

    private final GraphHopperConfig config;
    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;
    private final GHRequestTransformer ghRequestTransformer;
    private final Boolean hasElevation;
    private final String osmDate;
    private final List<String> snapPreventionsDefault;

    public RouteResource(GraphHopperConfig config, GraphHopper graphHopper, ProfileResolver profileResolver, GHRequestTransformer ghRequestTransformer, @Qualifier("hasElevation") Boolean hasElevation) {
        this.config = config;
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.ghRequestTransformer = ghRequestTransformer;
        this.hasElevation = hasElevation;
        this.osmDate = graphHopper.getProperties().getAll().get("datareader.data.date");
        this.snapPreventionsDefault = Arrays.stream(config.getString("routing.snap_preventions_default", "")
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, "application/gpx+xml"})
    public ResponseEntity<?> doGet(
            HttpServletRequest httpReq,
            @RequestParam(name = WAY_POINT_MAX_DISTANCE, defaultValue = "0.5") double minPathPrecision,
            @RequestParam(name = ELEVATION_WAY_POINT_MAX_DISTANCE, required = false) Double minPathElevationPrecision,
            @RequestParam(name = "point", required = false) List<GHPointParam> pointParams,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = INSTRUCTIONS, defaultValue = "true") boolean instructions,
            @RequestParam(name = CALC_POINTS, defaultValue = "true") boolean calcPoints,
            @RequestParam(name = "elevation", defaultValue = "false") boolean enableElevation,
            @RequestParam(name = "points_encoded", defaultValue = "true") boolean pointsEncoded,
            @RequestParam(name = "points_encoded_multiplier", defaultValue = "1e5") double pointsEncodedMultiplier,
            @RequestParam(name = "profile", required = false) String profileName,
            @RequestParam(name = ALGORITHM, required = false) String algoStr,
            @RequestParam(name = "locale", required = false) String localeStr,
            @RequestParam(name = POINT_HINT, required = false) List<String> pointHints,
            @RequestParam(name = CURBSIDE, required = false) List<String> curbsides,
            @RequestParam(name = SNAP_PREVENTION, required = false) List<String> snapPreventions,
            @RequestParam(name = PATH_DETAILS, required = false) List<String> pathDetails,
            @RequestParam(name = "heading", required = false) List<Double> headings,
            @RequestParam(name = "gpx.route", defaultValue = "true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
            @RequestParam(name = "gpx.track", defaultValue = "true") boolean withTrack,
            @RequestParam(name = "gpx.waypoints", defaultValue = "false") boolean withWayPoints,
            @RequestParam(name = "gpx.trackname", required = false) String trackName,
            @RequestParam(name = "gpx.millis", required = false) String timeString) {
        StopWatch sw = new StopWatch().start();
        // absent parameters: an empty value is passed on as it is, absent lists are empty
        type = type == null ? "json" : type;
        algoStr = algoStr == null ? "" : algoStr;
        localeStr = localeStr == null ? "en" : localeStr;
        trackName = trackName == null ? "GraphHopper Track" : trackName;
        pointHints = orEmpty(pointHints);
        curbsides = orEmpty(curbsides);
        snapPreventions = orEmpty(snapPreventions);
        pathDetails = orEmpty(pathDetails);
        headings = orEmpty(headings);
        Map<String, List<String>> queryParameters = QueryParameters.parse(httpReq.getQueryString());
        List<GHPoint> points = orEmpty(pointParams).stream().map(AbstractParam::get).collect(toList());
        boolean writeGPX = "gpx".equalsIgnoreCase(type);
        instructions = writeGPX || instructions;
        if (enableElevation && !hasElevation)
            throw new IllegalArgumentException("Elevation not supported!");

        GHRequest request = new GHRequest();
        initHints(request.getHints(), queryParameters);

        if (minPathElevationPrecision != null)
            request.getHints().putObject(ELEVATION_WAY_POINT_MAX_DISTANCE, minPathElevationPrecision);

        request.setPoints(points).
                setProfile(profileName).
                setAlgorithm(algoStr).
                setLocale(localeStr).
                setHeadings(headings).
                setPointHints(pointHints).
                setCurbsides(curbsides).
                setPathDetails(pathDetails).
                getHints().
                putObject(CALC_POINTS, calcPoints).
                putObject(INSTRUCTIONS, instructions).
                putObject(WAY_POINT_MAX_DISTANCE, minPathPrecision);

        if (queryParameters.containsKey(SNAP_PREVENTION)) {
            if (snapPreventions.size() == 1 && snapPreventions.contains(""))
                request.setSnapPreventions(List.of()); // e.g. "&snap_prevention=&" to force empty list
            else
                request.setSnapPreventions(snapPreventions);
        } else {
            // no "snap_prevention" was specified
            request.setSnapPreventions(snapPreventionsDefault);
        }

        request = ghRequestTransformer.transformRequest(request);

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", profileName);
        profileResolverHints.putObject("has_curbsides", !curbsides.isEmpty());
        profileName = profileResolver.resolveProfile(profileResolverHints);
        removeLegacyParameters(request.getHints());
        request.setProfile(profileName);

        GHResponse ghResponse = graphHopper.route(request);

        double took = sw.stop().getMillisDouble();
        String logStr = (httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent")) + " " + points + ", took: " + String.format("%.1f", took) + "ms, algo: " + algoStr + ", profile: " + profileName;

        if (ghResponse.hasErrors()) {
            logger.info(logStr + " " + ghResponse);
            return ResponseEntity.badRequest().
                    contentType(writeGPX ? GPX : MediaType.APPLICATION_JSON).
                    body(new MultiException(ghResponse.getErrors()));
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().size()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return writeGPX ?
                    gpxSuccessResponseBuilder(ghResponse, timeString, trackName, enableElevation, withRoute, withTrack, withWayPoints, Constants.VERSION, Math.round(took))
                    :
                    ResponseEntity.ok().
                            header("X-GH-Took", "" + Math.round(took)).
                            contentType(MediaType.APPLICATION_JSON).
                            body(ResponsePathSerializer.jsonObject(ghResponse, new ResponsePathSerializer.Info(config.getCopyrights(), Math.round(took), osmDate), instructions, calcPoints, enableElevation, pointsEncoded, pointsEncodedMultiplier));
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> doPost(@RequestBody(required = false) @NotNull GHRequest request, HttpServletRequest httpReq) {
        if (!request.hasSnapPreventions())
            request.setSnapPreventions(snapPreventionsDefault);

        StopWatch sw = new StopWatch().start();
        request = ghRequestTransformer.transformRequest(request);

        if (Helper.isEmpty(request.getProfile()) && request.getCustomModel() != null)
            // throw a dedicated exception here, otherwise a missing profile is still caught in Router
            throw new IllegalArgumentException("The 'profile' parameter is required when you use the `custom_model` parameter");

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", request.getProfile());
        profileResolverHints.putObject("has_curbsides", !request.getCurbsides().isEmpty());
        request.setProfile(profileResolver.resolveProfile(profileResolverHints));
        removeLegacyParameters(request.getHints());

        GHResponse ghResponse = graphHopper.route(request);
        boolean instructions = request.getHints().getBool(INSTRUCTIONS, true);
        boolean enableElevation = request.getHints().getBool("elevation", false);
        boolean calcPoints = request.getHints().getBool(CALC_POINTS, true);
        boolean pointsEncoded = request.getHints().getBool("points_encoded", true);
        double pointsEncodedMultiplier = request.getHints().getDouble("points_encoded_multiplier", 1e5);

        double took = sw.stop().getMillisDouble();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = infoStr + " " + request.getPoints().size() + ", took: "
                + String.format("%.1f", took) + " ms, algo: " + request.getAlgorithm() + ", profile: " + request.getProfile()
                + ", custom_model: " + request.getCustomModel();

        if (ghResponse.hasErrors()) {
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().size()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return ResponseEntity.ok().
                    header("X-GH-Took", "" + Math.round(took)).
                    contentType(MediaType.APPLICATION_JSON).
                    body(ResponsePathSerializer.jsonObject(ghResponse, new ResponsePathSerializer.Info(config.getCopyrights(), Math.round(took), osmDate), instructions, calcPoints, enableElevation, pointsEncoded, pointsEncodedMultiplier));
        }
    }

    public static void removeLegacyParameters(PMap hints) {
        // these parameters should only be used to resolve the profile, but should not be passed to GraphHopper
        hints.remove("weighting");
        hints.remove("vehicle");
        hints.remove("edge_based");
        hints.remove("turn_costs");
    }

    private static ResponseEntity<String> gpxSuccessResponseBuilder(GHResponse ghRsp, String timeString, String
            trackName, boolean enableElevation, boolean withRoute, boolean withTrack, boolean withWayPoints, String version, long took) {
        if (ghRsp.getAll().size() > 1) {
            throw new IllegalArgumentException("Alternatives are currently not yet supported for GPX");
        }

        long time = timeString != null ? Long.parseLong(timeString) : System.currentTimeMillis();
        InstructionList instructions = ghRsp.getBest().getInstructions();
        return ResponseEntity.ok().
                contentType(GPX).
                header("Content-Disposition", "attachment;filename=" + "GraphHopper.gpx").
                header("X-GH-Took", "" + took).
                body(GpxConversions.createGPX(instructions, trackName, time, enableElevation, withRoute, withTrack, withWayPoints, version, instructions.getTr()));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static void initHints(PMap m, Map<String, List<String>> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.putObject(Helper.camelCaseToUnderScore(e.getKey()), Helper.toObject(e.getValue().get(0)));
            } else {
                // TODO e.g. 'point' parameter occurs multiple times and we cannot throw an exception here
                //  unknown parameters (hints) should be allowed to be multiparameters, too, or we shouldn't use them for
                //  known parameters either, _or_ known parameters must be filtered before they come to this code point,
                //  _or_ we stop passing unknown parameters altogether.
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
                // see also #1976
            }
        }
    }
}
