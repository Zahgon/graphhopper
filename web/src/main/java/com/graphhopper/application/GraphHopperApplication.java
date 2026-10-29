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

import com.graphhopper.application.assets.AssetServlet;
import com.graphhopper.application.cli.*;
import com.graphhopper.application.resources.RootResource;
import com.graphhopper.http.CORSFilter;
import com.graphhopper.http.GraphHopperBundle;
import com.graphhopper.navigation.NavigateResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = ErrorMvcAutoConfiguration.class)
@Import({GraphHopperBundle.class, RootResource.class, NavigateResource.class})
public class GraphHopperApplication {

    public static void main(String[] args) {
        Cli cli = new Cli("java -jar graphhopper-web.jar",
                List.of(new ServerCommand(), new CheckCommand(), new ImportCommand(), new MatchCommand()), System.out, System.err);
        if (!cli.run(args))
            System.exit(1);
    }

    /**
     * The settings of the web server where the configuration file has none.
     */
    public static Map<String, Object> defaultProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.jmx.enabled", "false");
        properties.put("spring.web.resources.add-mappings", "false");
        properties.put("spring.servlet.multipart.enabled", "false");
        properties.put("server.port", "8080");
        properties.put("management.server.port", "8081");
        properties.put("management.endpoints.access.default", "none");
        properties.put("management.endpoints.web.exposure.exclude", "*");
        return properties;
    }

    /**
     * Starts the web server and the admin server.
     */
    public static ConfigurableApplicationContext start(GraphHopperServerConfiguration configuration) {
        SpringApplication application = new SpringApplication(GraphHopperApplication.class);
        Map<String, Object> properties = defaultProperties();
        properties.putAll(configuration.getServerProperties());
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("graphHopperServerConfiguration", configuration));
        return application.run();
    }

    @Bean
    public FilterRegistrationBean<CORSFilter> corsFilter() {
        FilterRegistrationBean<CORSFilter> registration = new FilterRegistrationBean<>(new CORSFilter());
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public ServletRegistrationBean<AssetServlet> mapsAssets() {
        ServletRegistrationBean<AssetServlet> registration = new ServletRegistrationBean<>(
                new AssetServlet("/com/graphhopper/maps/", "/maps/", "index.html", StandardCharsets.UTF_8), "/maps/*");
        registration.setName("maps-assets");
        return registration;
    }

    @Bean
    public ServletRegistrationBean<AssetServlet> webjarsAssets() {
        // see this link even though its outdated?! // https://www.webjars.org/documentation#dropwizard
        ServletRegistrationBean<AssetServlet> registration = new ServletRegistrationBean<>(
                new AssetServlet("/META-INF/resources/webjars", "/webjars/", null, StandardCharsets.UTF_8), "/webjars/*");
        registration.setName("webjars");
        return registration;
    }
}
