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
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * If the "?type=gpx" parameter is present, sets a corresponding media type header
 */
public class TypeGPXFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        List<String> maybeType = QueryParameters.parse(request.getQueryString()).get("type");
        if (maybeType != null && maybeType.get(0).equals("gpx")) {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    return HttpHeaders.ACCEPT.equalsIgnoreCase(name) ? "application/gpx+xml" : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return HttpHeaders.ACCEPT.equalsIgnoreCase(name) ? Collections.enumeration(List.of("application/gpx+xml")) : super.getHeaders(name);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    List<String> names = Collections.list(super.getHeaderNames());
                    if (names.stream().noneMatch(HttpHeaders.ACCEPT::equalsIgnoreCase))
                        names.add(HttpHeaders.ACCEPT);
                    return Collections.enumeration(names);
                }
            }, response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
