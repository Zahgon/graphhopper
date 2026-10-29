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

import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Responses to GET and POST requests are gzip compressed when the client accepts it and the body has at least 256
 * bytes; all mime types except Jetty's default exclusions (images, archives ...) are compressible and get
 * "Vary: Accept-Encoding". Gzip compressed request bodies are inflated.
 */
public class JettyCompressionCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {
    static final int MIN_GZIP_SIZE = 256;
    static final int INFLATE_BUFFER_SIZE = 8192;

    @Override
    public void customize(JettyServletWebServerFactory factory) {
        factory.addServerCustomizers(server -> {
            GzipHandler gzip = new GzipHandler();
            gzip.setMinGzipSize(MIN_GZIP_SIZE);
            gzip.setIncludedMethods("GET", "POST");
            gzip.setInflateBufferSize(INFLATE_BUFFER_SIZE);
            gzip.setHandler(server.getHandler());
            server.setHandler(gzip);
        });
    }
}
