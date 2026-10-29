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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.GHLocation;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.http.DurationParam;
import com.graphhopper.http.GHLocationParam;
import com.graphhopper.http.OffsetDateTimeParam;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.util.Helper;
import com.graphhopper.util.StopWatch;
import com.graphhopper.http.AbstractParam;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@RestController
@RequestMapping("/route-pt")
public class PtRouteResource {

    /**
     * The names of the routers that can be chosen with the pt.algorithm parameter.
     */
    public static final String CLASSIC = "classic";
    public static final String FREE_WALK = "free_walk";
    public static final String TRIP_BASED = "trip_based";

    private final GraphHopperConfig config;
    private final ObjectProvider<PtRouter> ptRouter;
    private final BeanFactory beanFactory;

    public PtRouteResource(GraphHopperConfig config, ObjectProvider<PtRouter> ptRouter, BeanFactory beanFactory) {
        this.config = config;
        this.ptRouter = ptRouter;
        this.beanFactory = beanFactory;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode route(@RequestParam(name = "point", required = false) @Size(min=2,max=2) List<GHLocationParam> requestPoints,
                            @RequestParam(name = "pt.earliest_departure_time", required = false) @NotNull OffsetDateTimeParam departureTimeParam,
                            @RequestParam(name = "pt.profile_duration", required = false) DurationParam profileDuration,
                            @RequestParam(name = "pt.arrive_by", defaultValue = "false") boolean arriveBy,
                            @RequestParam(name = "locale", required = false) String localeStr,
                            @RequestParam(name = "pt.ignore_transfers", required = false) Boolean ignoreTransfers,
                            @RequestParam(name = "pt.profile", required = false) Boolean profileQuery,
                            @RequestParam(name = "pt.limit_solutions", required = false) Integer limitSolutions,
                            @RequestParam(name = "pt.limit_trip_time", required = false) DurationParam limitTripTime,
                            @RequestParam(name = "pt.limit_street_time", required = false) DurationParam limitStreetTime,
                            @RequestParam(name = "pt.access_profile", required = false) String accessProfile,
                            @RequestParam(name = "pt.beta_access_time", required = false) Double betaAccessTime,
                            @RequestParam(name = "pt.egress_profile", required = false) String egressProfile,
                            @RequestParam(name = "pt.beta_egress_time", required = false) Double betaEgressTime,
                            @RequestParam(name = "pt.algorithm", required = false) String algorithm) {
        PtRouter ptRouter = algorithm == null ? this.ptRouter.getObject()
                : List.of(CLASSIC, FREE_WALK, TRIP_BASED).contains(algorithm) ? beanFactory.getBean(algorithm, PtRouter.class) : null;

        StopWatch stopWatch = new StopWatch().start();
        List<GHLocation> points = requestPoints.stream().map(AbstractParam::get).collect(toList());
        Instant departureTime = departureTimeParam.get().toInstant();

        Request request = new Request(points, departureTime);
        request.setArriveBy(arriveBy);
        Optional.ofNullable(profileQuery).ifPresent(request::setProfileQuery);
        Optional.ofNullable(profileDuration.get()).ifPresent(request::setMaxProfileDuration);
        Optional.ofNullable(ignoreTransfers).ifPresent(request::setIgnoreTransfers);
        Optional.ofNullable(localeStr).ifPresent(s -> request.setLocale(Helper.getLocale(s)));
        Optional.ofNullable(limitSolutions).ifPresent(request::setLimitSolutions);
        Optional.ofNullable(limitTripTime.get()).ifPresent(request::setLimitTripTime);
        Optional.ofNullable(limitStreetTime.get()).ifPresent(request::setLimitStreetTime);
        Optional.ofNullable(accessProfile).ifPresent(request::setAccessProfile);
        Optional.ofNullable(betaAccessTime).ifPresent(request::setBetaAccessTime);
        Optional.ofNullable(egressProfile).ifPresent(request::setEgressProfile);
        Optional.ofNullable(betaEgressTime).ifPresent(request::setBetaEgressTime);

        GHResponse route = ptRouter.route(request);
        return ResponsePathSerializer.jsonObject(route, new ResponsePathSerializer.Info(config.getCopyrights(), Math.round(stopWatch.stop().getMillis()), null), true, true, false, false, -1);
    }

}
