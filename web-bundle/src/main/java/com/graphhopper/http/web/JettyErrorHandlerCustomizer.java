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
package com.graphhopper.http.web;

import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Errors that servlets send with {@code sendError} (or that Jetty detects itself) are written by Jetty's plain error
 * handler: an HTML, JSON or text page depending on the Accept header, for GET, POST and HEAD requests only, without
 * forwarding to an error page of the application.
 */
public class JettyErrorHandlerCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {

    @Override
    public void customize(JettyServletWebServerFactory factory) {
        factory.addServerCustomizers(server -> server.setErrorHandler(new ErrorHandler()));
        // runs after the configuration that installs Spring Boot's error handler
        factory.addConfigurations(new AbstractConfiguration(new AbstractConfiguration.Builder()) {
            @Override
            public void configure(WebAppContext context) {
                context.setErrorHandler(new ErrorHandler());
            }
        });
    }
}
