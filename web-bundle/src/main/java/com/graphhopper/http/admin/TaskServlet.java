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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.*;

/**
 * The tasks of the admin server: GET /tasks lists them, POST /tasks/{name} runs one with the request parameters.
 */
public class TaskServlet extends HttpServlet {

    /**
     * A task that can be run by a POST request.
     */
    public interface Task {
        String getName();

        void execute(Map<String, List<String>> parameters, PrintWriter output) throws Exception;
    }

    private final Map<String, Task> tasks = new LinkedHashMap<>();

    public TaskServlet(List<Task> tasks) {
        for (Task task : tasks)
            this.tasks.put("/" + task.getName(), task);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty()) {
            resp.setContentType("text/plain");
            try (PrintWriter output = resp.getWriter()) {
                for (Task task : tasks.values())
                    output.println(task.getName());
            }
        } else if (tasks.containsKey(pathInfo)) {
            emptyError(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        } else {
            emptyError(resp, HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * An empty error answer without any header set so far, not even "Vary".
     */
    private static void emptyError(HttpServletResponse resp, int status) {
        resp.reset();
        resp.setStatus(status);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        Task task = pathInfo == null ? null : tasks.get(pathInfo);
        if (task == null) {
            emptyError(resp, HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("text/plain;charset=UTF-8");
        try (PrintWriter output = resp.getWriter()) {
            try {
                task.execute(parameters(req), output);
            } catch (Exception e) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                output.println();
                output.println(e.getMessage());
                output.flush();
            }
        }
    }

    private static Map<String, List<String>> parameters(HttpServletRequest req) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : req.getParameterMap().entrySet())
            parameters.put(entry.getKey(), List.of(entry.getValue()));
        return parameters;
    }

    /**
     * Runs the garbage collector.
     */
    public static class GarbageCollectionTask implements Task {
        @Override
        public String getName() {
            return "gc";
        }

        @Override
        public void execute(Map<String, List<String>> parameters, PrintWriter output) {
            output.println("Running GC...");
            output.flush();
            Runtime.getRuntime().gc();
            output.println("Done!");
        }
    }

    /**
     * Sets the level of the loggers named by the logger parameters to the level parameter, optionally only for the
     * time given by the duration parameter.
     */
    public static class LogConfigurationTask implements Task {
        private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        private final Timer timer = new Timer("log-level-reset", true);

        @Override
        public String getName() {
            return "log-level";
        }

        @Override
        public void execute(Map<String, List<String>> parameters, PrintWriter output) {
            List<String> loggerNames = parameters.getOrDefault("logger", List.of());
            List<String> levels = parameters.getOrDefault("level", List.of());
            Level level = levels.isEmpty() ? null : Level.toLevel(levels.get(0));
            List<String> durations = parameters.getOrDefault("duration", List.of());
            Duration duration = durations.isEmpty() ? null : Duration.parse(durations.get(0));

            for (String loggerName : loggerNames) {
                Logger logger = loggerContext.getLogger(loggerName);
                String message = String.format("Configured logging level for %s to %s", loggerName, level);
                if (level != null && duration != null) {
                    long millis = duration.toMillis();
                    Level previous = logger.getLevel();
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            logger.setLevel(previous);
                        }
                    }, millis);
                    message += String.format(" for %s milliseconds", millis);
                }
                logger.setLevel(level);
                output.println(message);
                output.flush();
            }
        }
    }
}
