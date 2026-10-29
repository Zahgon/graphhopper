package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.http.GHPointParam;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.http.QueryParameters;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import java.io.*;
import java.util.*;

import static com.graphhopper.resources.RouteResource.removeLegacyParameters;
import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;
import static com.graphhopper.util.Parameters.Details.STREET_NAME;

/**
 * This resource provides the entire shortest path tree as response. In a simple CSV format discussed at #1577.
 */
@RestController
@RequestMapping("/spt")
public class SPTResource {

    private static final Logger logger = LoggerFactory.getLogger(SPTResource.class);

    public static class IsoLabelWithCoordinates {
        public int nodeId = -1;
        public int edgeId, prevEdgeId, prevNodeId = -1;
        public int timeMillis, prevTimeMillis;
        public int distance, prevDistance;
        public GHPoint coordinate, prevCoordinate;
    }

    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;
    private final EncodingManager encodingManager;

    public SPTResource(GraphHopper graphHopper, ProfileResolver profileResolver, EncodingManager encodingManager) {
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.encodingManager = encodingManager;
    }

    // Annotating this as application/json because errors come out as json, and
    // IllegalArgumentExceptions are not mapped to a fixed mediatype, because in RouteResource, it could be GPX.
    @GetMapping(produces = {"text/csv", "application/json"})
    public void doGet(
            HttpServletRequest httpReq,
            HttpServletResponse httpRes,
            @RequestParam(name = "profile", required = false) String profileName,
            @RequestParam(name = "reverse_flow", defaultValue = "false") boolean reverseFlow,
            @RequestParam(name = "point", required = false) @NotNull GHPointParam point,
            @RequestParam(name = "columns", required = false) String columnsParam,
            @RequestParam(name = "time_limit", required = false) OptionalLong timeLimitInSeconds,
            @RequestParam(name = "distance_limit", required = false) OptionalLong distanceInMeter) throws IOException {
        StopWatch sw = new StopWatch().start();
        timeLimitInSeconds = timeLimitInSeconds == null ? OptionalLong.of(600) : timeLimitInSeconds;
        distanceInMeter = distanceInMeter == null ? OptionalLong.of(-1) : distanceInMeter;
        PMap hintsMap = new PMap();
        RouteResource.initHints(hintsMap, QueryParameters.parse(httpReq.getQueryString()));
        hintsMap.putObject(Parameters.CH.DISABLE, true);
        hintsMap.putObject(Parameters.Landmark.DISABLE, true);

        PMap profileResolverHints = new PMap(hintsMap);
        profileResolverHints.putObject("profile", profileName);
        profileName = profileResolver.resolveProfile(profileResolverHints);
        removeLegacyParameters(hintsMap);

        Profile profile = graphHopper.getProfile(profileName);
        if (profile == null)
            throw new IllegalArgumentException("The requested profile '" + profileName + "' does not exist");
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        BaseGraph graph = graphHopper.getBaseGraph();
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        BooleanEncodedValue inSubnetworkEnc = graphHopper.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(profileName));
        Snap snap = locationIndex.findClosest(point.get().lat, point.get().lon, new DefaultSnapFilter(weighting, inSubnetworkEnc));
        if (!snap.isValid())
            throw new IllegalArgumentException("Point not found:" + point);
        QueryGraph queryGraph = QueryGraph.create(graph, snap);
        NodeAccess nodeAccess = queryGraph.getNodeAccess();
        TraversalMode traversalMode = profile.hasTurnCosts() ? EDGE_BASED : NODE_BASED;
        ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, queryGraph.wrapWeighting(weighting), reverseFlow, traversalMode);

        if (distanceInMeter.orElseThrow(() -> new IllegalArgumentException("query param distance_limit is not a number.")) > 0) {
            shortestPathTree.setDistanceLimit(distanceInMeter.getAsLong());
        } else {
            double limit = timeLimitInSeconds.orElseThrow(() -> new IllegalArgumentException("query param time_limit is not a number.")) * 1000d;
            shortestPathTree.setTimeLimit(limit);
        }

        final String COL_SEP = ",", LINE_SEP = "\n";
        List<String> columns;
        if (!Helper.isEmpty(columnsParam))
            columns = Arrays.asList(columnsParam.split(","));
        else
            columns = Arrays.asList("longitude", "latitude", "time", "distance");

        if (columns.isEmpty())
            throw new IllegalArgumentException("Either omit the columns parameter or specify the columns via comma separated values");

        Map<String, EncodedValue> pathDetails = new HashMap<>();
        for (String col : columns) {
            if (encodingManager.hasEncodedValue(col))
                pathDetails.put(col, encodingManager.getEncodedValue(col, EncodedValue.class));
        }

        Writer writer = new BufferedWriter(new OutputStreamWriter(new CommitLaterOutputStream(httpRes), Helper.UTF_CS));
        StringBuilder sb = new StringBuilder();
        for (String col : columns) {
            if (sb.length() > 0)
                sb.append(COL_SEP);
            sb.append(col);
        }
        sb.append(LINE_SEP);
        writer.write(sb.toString());
        shortestPathTree.search(snap.getClosestNode(), l -> {
            IsoLabelWithCoordinates label = isoLabelWithCoordinates(nodeAccess, l);
            sb.setLength(0);
            for (int colIndex = 0; colIndex < columns.size(); colIndex++) {
                String col = columns.get(colIndex);
                if (colIndex > 0)
                    sb.append(COL_SEP);

                switch (col) {
                    case "node_id":
                        sb.append(label.nodeId);
                        continue;
                    case "prev_node_id":
                        sb.append(label.prevNodeId);
                        continue;
                    case "edge_id":
                        sb.append(label.edgeId);
                        continue;
                    case "prev_edge_id":
                        sb.append(label.prevEdgeId);
                        continue;
                    case "distance":
                        sb.append(label.distance);
                        continue;
                    case "prev_distance":
                        sb.append(label.prevCoordinate == null ? 0 : label.prevDistance);
                        continue;
                    case "time":
                        sb.append(label.timeMillis);
                        continue;
                    case "prev_time":
                        sb.append(label.prevCoordinate == null ? 0 : label.prevTimeMillis);
                        continue;
                    case "longitude":
                        sb.append(Helper.round6(label.coordinate.lon));
                        continue;
                    case "prev_longitude":
                        sb.append(label.prevCoordinate == null ? null : Helper.round6(label.prevCoordinate.lon));
                        continue;
                    case "latitude":
                        sb.append(Helper.round6(label.coordinate.lat));
                        continue;
                    case "prev_latitude":
                        sb.append(label.prevCoordinate == null ? null : Helper.round6(label.prevCoordinate.lat));
                        continue;
                }

                if (!EdgeIterator.Edge.isValid(label.edgeId))
                    continue;

                EdgeIteratorState edge = queryGraph.getEdgeIteratorState(label.edgeId, label.nodeId);
                if (edge == null)
                    continue;

                if (col.equals(STREET_NAME)) {
                    sb.append(edge.getName().replaceAll(",", ""));
                    continue;
                }

                EncodedValue ev = pathDetails.get(col);
                if (ev instanceof DecimalEncodedValue) {
                    DecimalEncodedValue dev = (DecimalEncodedValue) ev;
                    sb.append(reverseFlow ? edge.getReverse(dev) : edge.get(dev));
                } else if (ev instanceof EnumEncodedValue) {
                    EnumEncodedValue eev = (EnumEncodedValue) ev;
                    sb.append(reverseFlow ? edge.getReverse(eev) : edge.get(eev));
                } else if (ev instanceof BooleanEncodedValue) {
                    BooleanEncodedValue eev = (BooleanEncodedValue) ev;
                    sb.append(reverseFlow ? edge.getReverse(eev) : edge.get(eev));
                } else if (ev instanceof IntEncodedValue) {
                    IntEncodedValue eev = (IntEncodedValue) ev;
                    sb.append(reverseFlow ? edge.getReverse(eev) : edge.get(eev));
                } else {
                    throw new IllegalArgumentException("Unknown property " + col);
                }
            }
            sb.append(LINE_SEP);
            try {
                writer.write(sb.toString());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        writer.close();
        logger.info("took: " + sw.stop().getSeconds() + ", visited nodes:" + shortestPathTree.getVisitedNodes() + ", " + QueryParameters.parse(httpReq.getQueryString()));
    }

    /**
     * Keeps the first bytes of the CSV in memory, so that an error found while writing them can still be answered with
     * an error response instead of a broken CSV. The response is committed (as text/csv, since errors are JSON) once
     * the buffer is full or the CSV is complete.
     */
    private static class CommitLaterOutputStream extends OutputStream {
        private static final int BUFFER_SIZE = 8192;
        private final HttpServletResponse response;
        private ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUFFER_SIZE);
        private OutputStream target;

        CommitLaterOutputStream(HttpServletResponse response) {
            this.response = response;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (target != null) {
                target.write(b, off, len);
                return;
            }
            buffer.write(b, off, len);
            if (buffer.size() >= BUFFER_SIZE)
                commit();
        }

        private void commit() throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/csv");
            target = response.getOutputStream();
            buffer.writeTo(target);
            buffer = null;
        }

        @Override
        public void close() throws IOException {
            if (target == null)
                commit();
            target.flush();
        }
    }

    private IsoLabelWithCoordinates isoLabelWithCoordinates(NodeAccess na, ShortestPathTree.IsoLabel label) {
        double lat = na.getLat(label.node);
        double lon = na.getLon(label.node);
        IsoLabelWithCoordinates isoLabelWC = new IsoLabelWithCoordinates();
        isoLabelWC.nodeId = label.node;
        isoLabelWC.coordinate = new GHPoint(lat, lon);
        isoLabelWC.timeMillis = Math.round(label.time);
        isoLabelWC.distance = (int) Math.round(label.distance);
        isoLabelWC.edgeId = label.edge;
        if (label.parent != null) {
            ShortestPathTree.IsoLabel prevLabel = label.parent;
            int prevNodeId = prevLabel.node;
            double prevLat = na.getLat(prevNodeId);
            double prevLon = na.getLon(prevNodeId);
            isoLabelWC.prevNodeId = prevNodeId;
            isoLabelWC.prevEdgeId = prevLabel.edge;
            isoLabelWC.prevCoordinate = new GHPoint(prevLat, prevLon);
            isoLabelWC.prevDistance = (int) Math.round(prevLabel.distance);
            isoLabelWC.prevTimeMillis = Math.round(prevLabel.time);
        }
        return isoLabelWC;
    }
}
