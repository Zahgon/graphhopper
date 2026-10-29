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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;

/**
 * The pages of the admin server: the menu at /, /healthcheck, /metrics, /ping and /threads. Other paths are answered
 * with 404.
 */
public class AdminServlet extends HttpServlet {
    static final String CACHE_CONTROL = "must-revalidate,no-cache,no-store";

    private static final String TEMPLATE = String.format(
            "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"%n" +
                    "        \"http://www.w3.org/TR/html4/loose.dtd\">%n" +
                    "<html>%n" +
                    "<head>%n" +
                    "  <title>Metrics</title>%n" +
                    "</head>%n" +
                    "<body>%n" +
                    "  <h1>Operational Menu</h1>%n" +
                    "  <ul>%n" +
                    "    <li><a href=\"{0}{1}?pretty=true\">Metrics</a></li>%n" +
                    "    <li><a href=\"{2}{3}\">Ping</a></li>%n" +
                    "    <li><a href=\"{4}{5}\">Threads</a></li>%n" +
                    "    <li><a href=\"{6}{7}?pretty=true\">Healthcheck</a></li>%n" +
                    "    <li><a href=\"{8}{9}\">CPU Profile</a></li>%n" +
                    "    <li><a href=\"{10}{11}?state=blocked\">CPU Contention</a></li>%n" +
                    "  </ul>%n" +
                    "</body>%n" +
                    "</html>");

    static final String HEALTHCHECK_URI = "/healthcheck";
    static final String METRICS_URI = "/metrics";
    static final String PING_URI = "/ping";
    static final String THREADS_URI = "/threads";
    static final String CPU_PROFILE_URI = "/pprof";

    private final HttpServlet healthCheckServlet;
    private final HttpServlet metricsServlet;
    private final HttpServlet pingServlet = new PingServlet();
    private final HttpServlet threadDumpServlet = new ThreadDumpServlet();

    public AdminServlet(HttpServlet healthCheckServlet, HttpServlet metricsServlet) {
        this.healthCheckServlet = healthCheckServlet;
        this.metricsServlet = metricsServlet;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getContextPath() + req.getServletPath();
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setHeader("Cache-Control", CACHE_CONTROL);
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");
        try (PrintWriter writer = resp.getWriter()) {
            writer.println(MessageFormat.format(TEMPLATE, path, METRICS_URI, path, PING_URI, path, THREADS_URI, path,
                    HEALTHCHECK_URI, path, CPU_PROFILE_URI, path, CPU_PROFILE_URI));
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String uri = req.getPathInfo();
        if (uri == null || uri.equals("/"))
            super.service(req, resp);
        else if (uri.equals(HEALTHCHECK_URI))
            healthCheckServlet.service(req, resp);
        else if (uri.startsWith(METRICS_URI))
            metricsServlet.service(req, resp);
        else if (uri.equals(PING_URI))
            pingServlet.service(req, resp);
        else if (uri.equals(THREADS_URI))
            threadDumpServlet.service(req, resp);
        else
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Answers "pong".
     */
    static class PingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader("Cache-Control", CACHE_CONTROL);
            resp.setContentType("text/plain");
            try (PrintWriter writer = resp.getWriter()) {
                writer.println("pong");
            }
        }
    }
}
