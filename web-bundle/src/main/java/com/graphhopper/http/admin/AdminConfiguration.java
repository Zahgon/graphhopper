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
package com.graphhopper.http.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.http.health.HealthCheckRegistry;
import com.graphhopper.http.web.JettyCompressionCustomizer;
import com.graphhopper.http.web.JettyErrorHandlerCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * The servlets of the admin server, which runs on its own port (management.server.port).
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
@ConditionalOnBean(HealthCheckRegistry.class)
public class AdminConfiguration {

    @Bean
    public JettyErrorHandlerCustomizer adminJettyErrorHandlerCustomizer() {
        return new JettyErrorHandlerCustomizer();
    }

    @Bean
    public JettyCompressionCustomizer adminJettyCompressionCustomizer() {
        return new JettyCompressionCustomizer();
    }

    @Bean
    public ServletRegistrationBean<AdminServlet> adminServlet(HealthCheckRegistry healthCheckRegistry,
                                                              ObjectProvider<MeterRegistry> meterRegistry,
                                                              ObjectMapper objectMapper) {
        MeterRegistry registry = meterRegistry.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        AdminServlet servlet = new AdminServlet(new HealthCheckServlet(healthCheckRegistry, objectMapper),
                new MetricsServlet(registry, objectMapper));
        ServletRegistrationBean<AdminServlet> registration = new ServletRegistrationBean<>(servlet, "/*");
        registration.setName("admin");
        return registration;
    }

    @Bean
    public ServletRegistrationBean<TaskServlet> taskServlet() {
        TaskServlet servlet = new TaskServlet(List.of(new TaskServlet.GarbageCollectionTask(), new TaskServlet.LogConfigurationTask()));
        ServletRegistrationBean<TaskServlet> registration = new ServletRegistrationBean<>(servlet, "/tasks/*");
        registration.setName("tasks");
        return registration;
    }
}
