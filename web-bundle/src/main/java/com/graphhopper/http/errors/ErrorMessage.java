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
package com.graphhopper.http.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The generic error envelope of the service: {"code":404,"message":"HTTP 404 Not Found"}.
 */
@JsonPropertyOrder({"code", "message", "details"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ErrorMessage {
    private final int code;
    private final String message;
    private final String details;

    public ErrorMessage(int code, String message) {
        this(code, message, null);
    }

    public ErrorMessage(int code, String message, String details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }

    /**
     * The envelope used for plain HTTP errors, e.g. "HTTP 405 Method Not Allowed".
     */
    public static ErrorMessage forStatus(int code) {
        return new ErrorMessage(code, "HTTP " + code + " " + HttpStatusException.reasonPhrase(code));
    }

    @JsonProperty
    public int getCode() {
        return code;
    }

    @JsonProperty
    public String getMessage() {
        return message;
    }

    @JsonProperty
    public String getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
