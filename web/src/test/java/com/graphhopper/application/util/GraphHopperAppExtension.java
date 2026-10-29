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
package com.graphhopper.application.util;

import com.graphhopper.application.GraphHopperApplication;
import com.graphhopper.application.GraphHopperServerConfiguration;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the server for the tests of a class: started before the {@code @BeforeAll} methods, stopped after the
 * {@code @AfterAll} methods. Register it on a static field with {@code @RegisterExtension}.
 */
public class GraphHopperAppExtension implements BeforeAllCallback, AfterAllCallback {
    private final GraphHopperServerConfiguration configuration;
    private ConfigurableApplicationContext context;

    public GraphHopperAppExtension(GraphHopperServerConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        context = GraphHopperApplication.start(configuration);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        if (context != null)
            context.close();
        context = null;
    }

    public GraphHopperServerConfiguration getConfiguration() {
        return configuration;
    }

    public ConfigurableApplicationContext getContext() {
        if (context == null)
            throw new IllegalStateException("the server is not running");
        return context;
    }

    public int getLocalPort() {
        return ((WebServerApplicationContext) getContext()).getWebServer().getPort();
    }

    public int getAdminPort() {
        return getContext().getEnvironment().getRequiredProperty("local.management.port", Integer.class);
    }
}
