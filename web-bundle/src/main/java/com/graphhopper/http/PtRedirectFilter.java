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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Serves /route and /isochrone requests for ?vehicle=pt or ?profile=pt with /route-pt and /isochrone-pt, without
 * the vehicle and profile parameters. The path is compared decoded, so /r%6Fute is redirected as well.
 */
public class PtRedirectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (shouldRedirect(request)) {
            String path = UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()), StandardCharsets.UTF_8);
            if (path.equals("/route")) {
                chain.doFilter(new ForwardedRequest(request, "/route-pt"), response);
                return;
            } else if (path.equals("/isochrone")) {
                chain.doFilter(new ForwardedRequest(request, "/isochrone-pt"), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean shouldRedirect(HttpServletRequest request) {
        Map<String, List<String>> parameters = QueryParameters.parse(request.getQueryString());
        String maybeVehicle = first(parameters, "vehicle");
        String maybeProfile = first(parameters, "profile");
        return "pt".equals(maybeVehicle) || "pt".equals(maybeProfile);
    }

    private static String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.get(0);
    }

    private static class ForwardedRequest extends HttpServletRequestWrapper {
        private final String path;
        private final String queryString;
        private final Map<String, String[]> parameters;

        ForwardedRequest(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
            this.queryString = QueryParameters.without(request.getQueryString(), "vehicle", "profile");
            Map<String, String[]> parameters = new LinkedHashMap<>();
            QueryParameters.parse(queryString).forEach((name, values) -> parameters.put(name, values.toArray(new String[0])));
            this.parameters = Collections.unmodifiableMap(parameters);
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + path;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer(super.getRequestURL());
            url.setLength(url.length() - super.getRequestURI().length());
            return url.append(getRequestURI());
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public String getQueryString() {
            return queryString;
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }
    }
}
