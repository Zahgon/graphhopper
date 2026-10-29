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

import com.graphhopper.http.errors.ErrorMessage;
import com.graphhopper.http.errors.ParamException;

/**
 * A request parameter that parses its raw string value on construction. Parsing errors become a 400 answer with
 * the {"code":400,"message":"..."} envelope, the message naming the parameter, e.g. "query param point is invalid: ...".
 *
 * @param <T> the parsed type
 */
public abstract class AbstractParam<T> {
    private final String parameterName;
    private final T value;

    protected AbstractParam(String input) {
        this(input, "Parameter");
    }

    protected AbstractParam(String input, String parameterName) {
        this.parameterName = parameterName;
        try {
            this.value = parse(input);
        } catch (Exception e) {
            throw new ParamException(generateErrorMessage(e));
        }
    }

    protected ErrorMessage generateErrorMessage(Exception e) {
        String message = errorMessage(e);
        if (message.contains("%s"))
            message = String.format(message, parameterName);
        return new ErrorMessage(400, message);
    }

    /**
     * @return the error message for a value that could not be parsed, "%s" stands for the parameter name
     */
    protected String errorMessage(Exception e) {
        return String.format("%s is invalid: %s", "%s", e.getMessage());
    }

    protected abstract T parse(String input) throws Exception;

    public T get() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        return java.util.Objects.equals(value, ((AbstractParam<?>) obj).value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
