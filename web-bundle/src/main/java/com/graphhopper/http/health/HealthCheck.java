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
package com.graphhopper.http.health;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A check of the health of a part of the application. The result of {@link #execute()} also records how long the
 * check took and when it ran; exceptions of {@link #check()} make the result unhealthy.
 */
public abstract class HealthCheck {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"healthy", "message", "error", "duration", "timestamp"})
    public static final class Result {
        private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

        private final boolean healthy;
        private final String message;
        private final Throwable error;
        private long duration;
        private String timestamp;

        private Result(boolean healthy, String message, Throwable error) {
            this.healthy = healthy;
            this.message = message;
            this.error = error;
            this.timestamp = OffsetDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP);
        }

        public static Result healthy() {
            return new Result(true, null, null);
        }

        public static Result healthy(String message) {
            return new Result(true, message, null);
        }

        public static Result unhealthy(String message) {
            return new Result(false, message, null);
        }

        public static Result unhealthy(Throwable error) {
            return new Result(false, error.getMessage(), error);
        }

        public boolean isHealthy() {
            return healthy;
        }

        public String getMessage() {
            return message;
        }

        @JsonIgnore
        public Throwable getError() {
            return error;
        }

        @JsonProperty("error")
        Map<String, Object> getErrorDetails() {
            return error == null ? null : details(error);
        }

        private static Map<String, Object> details(Throwable error) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("type", error.getClass().getTypeName());
            details.put("message", error.getMessage());
            details.put("stack", Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString).toList());
            if (error.getCause() != null)
                details.put("cause", details(error.getCause()));
            return details;
        }

        /**
         * @return the time the check took in milliseconds
         */
        public long getDuration() {
            return duration;
        }

        public String getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return "Result{isHealthy=" + healthy + (message == null ? "" : ", message=" + message)
                    + (error == null ? "" : ", error=" + error) + ", duration=" + duration + ", timestamp=" + timestamp + "}";
        }
    }

    protected abstract Result check() throws Exception;

    public Result execute() {
        long start = System.nanoTime();
        Result result;
        try {
            result = check();
        } catch (Exception e) {
            result = Result.unhealthy(e);
        }
        result.duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return result;
    }
}
