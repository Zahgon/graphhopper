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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maps requests to the resources like JAX-RS does:
 * <ul>
 *     <li>the fixed parts of a path match the path as sent, so "/rou%74e" is not "/route", whatever the method and
 *     media types of the request</li>
 *     <li>a request without Content-Type matches any consumed media type, so it is rejected with 406 when the resource
 *     cannot produce an accepted media type</li>
 *     <li>a request with a malformed Accept header fails with a plain 400 error once path, method and consumed media
 *     type have matched, see {@link AcceptHeaderStrategy}</li>
 *     <li>OPTIONS requests are answered with the list of methods of the resource as header, "HEAD,GET,OPTIONS", and
 *     as plain text body "HEAD, GET, OPTIONS" unless the Accept header prefers another media type, see
 *     {@link OptionsHandler}</li>
 * </ul>
 */
public class GHRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    @Override
    protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
        ignoreParametersAfterTrailingSlash(request);
        HandlerMethod handler = super.getHandlerInternal(request);
        if (handler != null && !matchesSentPath(request, request.getAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE))) {
            request.removeAttribute(PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
            return null;
        }
        if (handler != null && AcceptHeaderStrategy.isMalformed(request))
            throw new AcceptHeaderStrategy.MalformedAcceptHeaderException(request.getHeader(HttpHeaders.ACCEPT));
        return handler;
    }

    /**
     * "/info/;a=1" is "/info/": the last segment consists of matrix parameters only.
     */
    private static void ignoreParametersAfterTrailingSlash(HttpServletRequest request) {
        if (!ServletRequestPathUtils.hasParsedRequestPath(request))
            return;
        RequestPath path = ServletRequestPathUtils.getParsedRequestPath(request);
        List<PathContainer.Element> elements = path.elements();
        if (elements.size() < 2 || !(elements.get(elements.size() - 1) instanceof PathContainer.PathSegment last)
                || !last.valueToMatch().isEmpty() || !last.value().startsWith(";"))
            return;
        String value = path.value();
        ServletRequestPathUtils.setParsedRequestPath(
                RequestPath.parse(value.substring(0, value.lastIndexOf('/') + 1), path.contextPath().value()), request);
    }

    private static boolean matchesSentPath(HttpServletRequest request, Object bestPattern) {
        String sent = request.getRequestURI().substring(request.getContextPath().length());
        if (sent.indexOf('%') < 0 || !(bestPattern instanceof String pattern))
            return true;
        String[] sentSegments = sent.split("/", -1);
        String[] patternSegments = pattern.split("/", -1);
        for (int i = 0; i < patternSegments.length && i < sentSegments.length; i++) {
            String segment = patternSegments[i];
            if (segment.startsWith("{*"))
                break;
            if (segment.contains("{") || segment.contains("*"))
                continue;
            String sentSegment = sentSegments[i];
            int matrix = sentSegment.indexOf(';');
            if (!segment.equals(matrix < 0 ? sentSegment : sentSegment.substring(0, matrix)))
                return false;
        }
        return true;
    }

    @Override
    protected HandlerMethod handleNoMatch(Set<RequestMappingInfo> infos, String lookupPath, HttpServletRequest request) throws ServletException {
        if (!anyPathMatchesSentPath(infos, request))
            return null;
        HandlerMethod handler;
        try {
            handler = super.handleNoMatch(infos, lookupPath, request);
        } catch (HttpMediaTypeNotSupportedException e) {
            if (request.getContentType() == null && !producesAcceptable(infos, request))
                throw new HttpMediaTypeNotAcceptableException(List.of());
            throw e;
        }
        if (handler == null || !HttpMethod.OPTIONS.matches(request.getMethod()))
            return handler;
        try {
            Object result = handler.getMethod().invoke(handler.getBean());
            if (!(result instanceof HttpHeaders headers))
                return handler;
            return new HandlerMethod(new OptionsHandler(headers.getAllow()), OptionsHandler.class.getMethod("handle", HttpServletRequest.class, HttpServletResponse.class));
        } catch (ReflectiveOperationException e) {
            throw new ServletException(e);
        }
    }

    /**
     * @return false if the path patterns that match the decoded path differ from the path as sent in a fixed part
     */
    private static boolean anyPathMatchesSentPath(Set<RequestMappingInfo> infos, HttpServletRequest request) {
        boolean pathMatched = false;
        for (RequestMappingInfo info : infos) {
            PathPatternsRequestCondition paths = info.getPathPatternsCondition();
            PathPatternsRequestCondition matching = paths == null ? null : paths.getMatchingCondition(request);
            if (matching == null)
                continue;
            pathMatched = true;
            if (matchesSentPath(request, matching.getFirstPattern().getPatternString()))
                return true;
        }
        return !pathMatched;
    }

    private boolean producesAcceptable(Set<RequestMappingInfo> infos, HttpServletRequest request) {
        for (RequestMappingInfo info : infos) {
            boolean candidate = (info.getPathPatternsCondition() == null || info.getPathPatternsCondition().getMatchingCondition(request) != null)
                    && info.getMethodsCondition().getMatchingCondition(request) != null;
            if (candidate && info.getProducesCondition().getMatchingCondition(request) != null)
                return true;
        }
        return false;
    }

    /**
     * Answers OPTIONS like the two OPTIONS methods of JAX-RS: one produces the methods as text/plain, the other one
     * produces what the writers of any entity produce and answers without entity, with the first accepted media range
     * as content type. The usual media type selection decides between them.
     */
    public static final class OptionsHandler {
        private static final List<MediaType> PRODUCED = List.of(MediaType.TEXT_PLAIN,
                MediaType.APPLICATION_JSON, MediaType.parseMediaType("text/json"), MediaType.APPLICATION_XML,
                MediaType.TEXT_XML, MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_FORM_URLENCODED,
                MediaType.parseMediaType("application/gpx+xml"), MediaType.parseMediaType("image/*"), MediaType.ALL);
        private final List<String> methods = new ArrayList<>();

        OptionsHandler(Set<HttpMethod> allowed) {
            if (allowed.contains(HttpMethod.HEAD))
                methods.add("HEAD");
            for (HttpMethod method : allowed)
                if (!method.equals(HttpMethod.HEAD) && !method.equals(HttpMethod.OPTIONS) && !method.equals(HttpMethod.GET))
                    methods.add(method.name());
            if (allowed.contains(HttpMethod.GET))
                methods.add("GET");
            methods.add("OPTIONS");
        }

        public ResponseEntity<String> handle(HttpServletRequest request, HttpServletResponse servletResponse) {
            List<AcceptHeaderStrategy.AcceptableType> accepted = AcceptHeaderStrategy.acceptableTypes(request);
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.ALLOW, String.join(",", methods));
            if (AcceptHeaderStrategy.selectWriter(accepted, PRODUCED) > 0) {
                // the accepted range is echoed as sent, even with a charset Spring's MediaType rejects
                servletResponse.setContentType(accepted.get(0).spelling());
                return response.build();
            }
            return response.contentType(MediaType.TEXT_PLAIN).body(String.join(", ", methods));
        }
    }
}
