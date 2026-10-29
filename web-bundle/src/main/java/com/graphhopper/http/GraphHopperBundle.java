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

package com.graphhopper.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.*;
import com.graphhopper.http.errors.GHExceptionHandler;
import com.graphhopper.http.health.GraphHopperHealthCheck;
import com.graphhopper.http.health.HealthCheckRegistry;
import com.graphhopper.http.health.ThreadDeadlockHealthCheck;
import com.graphhopper.http.web.GHWebMvcConfiguration;
import com.graphhopper.isochrone.algorithm.JTSTriangulator;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.resources.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The GraphHopper web service: the GraphHopper instance, started and stopped with the application, and the web
 * resources that use it. Needs a {@link GraphHopperBundleConfiguration} bean.
 * <p>
 * The public transit resources (/route-pt, /isochrone-pt, /pt-mvt) are only available if gtfs.file is configured.
 */
@Configuration(proxyBeanMethods = false)
@Import({GHWebMvcConfiguration.class, GHExceptionHandler.class,
        MVTResource.class, NearestResource.class, RouteResource.class, IsochroneResource.class, MapMatchingResource.class,
        SPTResource.class, I18NResource.class, InfoResource.class, HealthCheckResource.class,
        GraphHopperBundle.PtConfiguration.class})
public class GraphHopperBundle {

    /**
     * @return the object mapper for the requests and responses, also used to read the configuration
     */
    public static ObjectMapper newObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new GuavaModule());
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // avoids warning regarding com.fasterxml.jackson.databind.util.ClassUtil
        objectMapper.registerModule(new Jdk8Module());
        Jackson.initObjectMapper(objectMapper);
        objectMapper.setDateFormat(new StdDateFormat());
        // Make snake_case work for explicitly named properties of the configuration, too
        objectMapper.setConfig(objectMapper.getDeserializationConfig().with(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING));
        return objectMapper;
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return newObjectMapper();
    }

    @Bean
    public GraphHopperConfig graphHopperConfig(GraphHopperBundleConfiguration configuration) {
        for (Object k : System.getProperties().keySet()) {
            if (k instanceof String && ((String) k).startsWith("graphhopper."))
                throw new IllegalArgumentException("You need to prefix system parameters with '-Ddw.graphhopper.' instead of '-Dgraphhopper.' see #1879 and #1897");
        }
        return configuration.getGraphHopperConfiguration();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public GraphHopperManaged graphHopperManaged(GraphHopperConfig config) {
        return new GraphHopperManaged(config);
    }

    @Bean
    public GraphHopper graphHopper(GraphHopperManaged graphHopperManaged) {
        return graphHopperManaged.getGraphHopper();
    }

    @Bean
    public Triangulator triangulator(GraphHopper graphHopper) {
        return new JTSTriangulator(graphHopper.getRouterConfig());
    }

    @Bean
    public MapMatchingResource.MapMatchingRouterFactory mapMatchingRouterFactory(GraphHopper graphHopper) {
        return hints -> MapMatching.routerFromGraphHopper(graphHopper, hints);
    }

    @Bean
    public PathDetailsBuilderFactory pathDetailsBuilderFactory(GraphHopper graphHopper) {
        return graphHopper.getPathDetailsBuilderFactory();
    }

    @Bean
    public ProfileResolver profileResolver(GraphHopper graphHopper) {
        return new ProfileResolver(graphHopper.getProfiles());
    }

    @Bean
    public GHRequestTransformer ghRequestTransformer() {
        return req -> req;
    }

    @Bean
    public Boolean hasElevation(GraphHopper graphHopper) {
        return graphHopper.hasElevation();
    }

    @Bean
    public LocationIndex locationIndex(GraphHopper graphHopper) {
        return graphHopper.getLocationIndex();
    }

    @Bean
    public TranslationMap translationMap(GraphHopper graphHopper) {
        return graphHopper.getTranslationMap();
    }

    @Bean
    public EncodingManager encodingManager(GraphHopper graphHopper) {
        return graphHopper.getEncodingManager();
    }

    @Bean
    public BaseGraph baseGraph(GraphHopper graphHopper) {
        return graphHopper.getBaseGraph();
    }

    @Bean
    public ThreadDeadlockHealthCheck deadlocksHealthCheck() {
        return new ThreadDeadlockHealthCheck();
    }

    @Bean
    public GraphHopperHealthCheck graphHopperHealthCheck(GraphHopper graphHopper) {
        return new GraphHopperHealthCheck(graphHopper);
    }

    @Bean
    public HealthCheckRegistry healthCheckRegistry(ThreadDeadlockHealthCheck deadlocksHealthCheck,
                                                   GraphHopperHealthCheck graphHopperHealthCheck) {
        HealthCheckRegistry registry = new HealthCheckRegistry();
        registry.register("deadlocks", deadlocksHealthCheck);
        registry.register("graphhopper", graphHopperHealthCheck);
        return registry;
    }

    /**
     * Matches if the GraphHopper configuration has a gtfs.file.
     */
    static class GtfsCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConfigurableBeanFactory beanFactory = context.getBeanFactory();
            if (beanFactory == null)
                return false;
            String[] names = ((ListableBeanFactory) beanFactory).getBeanNamesForType(GraphHopperBundleConfiguration.class, false, false);
            return names.length > 0 && beanFactory.getBean(names[0], GraphHopperBundleConfiguration.class)
                    .getGraphHopperConfiguration().has("gtfs.file");
        }
    }

    /**
     * These are pt-specific implementations of /route and /isochrone, but the same API.
     * We serve them under different paths (/route-pt and /isochrone-pt), and forward
     * requests for ?vehicle=pt there.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(GtfsCondition.class)
    @Import({PtRouteResource.class, PtIsochroneResource.class, PtMVTResource.class})
    static class PtConfiguration {

        @Bean
        public GtfsStorage gtfsStorage(GraphHopper graphHopper) {
            return ((GraphHopperGtfs) graphHopper).getGtfsStorage();
        }

        @Bean
        public PtRedirectFilter ptRedirectFilter() {
            return new PtRedirectFilter();
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        @Conditional(RealtimeFeedsCondition.class)
        public RealtimeFeedLoadingCache realtimeFeedLoadingCache(GraphHopper graphHopper, GraphHopperBundleConfiguration configuration) {
            CloseableHttpClient httpClient = configuration.gtfsrealtime().getHttpClientConfiguration().build();
            return new RealtimeFeedLoadingCache((GraphHopperGtfs) graphHopper, httpClient, configuration);
        }

        /**
         * The realtime feed, fetched again for each request that uses it, as it changes over time.
         */
        @Bean
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public RealtimeFeed realtimeFeed(GraphHopperBundleConfiguration configuration, org.springframework.beans.factory.ObjectProvider<RealtimeFeedLoadingCache> cache) {
            RealtimeFeedLoadingCache loadingCache = cache.getIfAvailable();
            return loadingCache == null ? RealtimeFeed.empty() : loadingCache.provide();
        }

        @Bean
        @Primary
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PtRouter ptRouter(GraphHopperConfig config, GraphHopper graphHopper, TranslationMap translationMap, BaseGraph baseGraph,
                                 EncodingManager encodingManager, LocationIndex locationIndex, GtfsStorage gtfsStorage,
                                 RealtimeFeed realtimeFeed, PathDetailsBuilderFactory pathDetailsBuilderFactory) {
            if (config.getBool("gtfs.free_walk", false))
                return new PtRouterFreeWalkImpl(config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, realtimeFeed, pathDetailsBuilderFactory);
            else if (config.getBool("gtfs.trip_based", false))
                return new PtRouterTripBasedImpl(graphHopper, config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, pathDetailsBuilderFactory);
            else
                return new PtRouterImpl(config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, realtimeFeed, pathDetailsBuilderFactory);
        }

        @Bean(PtRouteResource.CLASSIC)
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PtRouter classicPtRouter(GraphHopperConfig config, TranslationMap translationMap, BaseGraph baseGraph,
                                        EncodingManager encodingManager, LocationIndex locationIndex, GtfsStorage gtfsStorage,
                                        RealtimeFeed realtimeFeed, PathDetailsBuilderFactory pathDetailsBuilderFactory) {
            return new PtRouterImpl(config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, realtimeFeed, pathDetailsBuilderFactory);
        }

        @Bean(PtRouteResource.FREE_WALK)
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PtRouter freeWalkPtRouter(GraphHopperConfig config, TranslationMap translationMap, BaseGraph baseGraph,
                                         EncodingManager encodingManager, LocationIndex locationIndex, GtfsStorage gtfsStorage,
                                         RealtimeFeed realtimeFeed, PathDetailsBuilderFactory pathDetailsBuilderFactory) {
            return new PtRouterFreeWalkImpl(config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, realtimeFeed, pathDetailsBuilderFactory);
        }

        @Bean(PtRouteResource.TRIP_BASED)
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PtRouter tripBasedPtRouter(GraphHopper graphHopper, GraphHopperConfig config, TranslationMap translationMap, BaseGraph baseGraph,
                                          EncodingManager encodingManager, LocationIndex locationIndex, GtfsStorage gtfsStorage,
                                          PathDetailsBuilderFactory pathDetailsBuilderFactory) {
            return new PtRouterTripBasedImpl(graphHopper, config, translationMap, baseGraph, encodingManager, locationIndex, gtfsStorage, pathDetailsBuilderFactory);
        }
    }

    /**
     * Matches if GTFS realtime feeds are configured.
     */
    static class RealtimeFeedsCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConfigurableBeanFactory beanFactory = context.getBeanFactory();
            if (beanFactory == null)
                return false;
            String[] names = ((ListableBeanFactory) beanFactory).getBeanNamesForType(GraphHopperBundleConfiguration.class, false, false);
            return names.length > 0 && !beanFactory.getBean(names[0], GraphHopperBundleConfiguration.class)
                    .gtfsrealtime().getFeeds().isEmpty();
        }
    }
}
