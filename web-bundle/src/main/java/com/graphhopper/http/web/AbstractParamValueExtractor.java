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

import com.graphhopper.http.AbstractParam;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * Constraints of {@link AbstractParam} parameters apply to their value, e.g. {@code @NotNull GHPointParam point}
 * fails if the point parameter is absent.
 */
@UnwrapByDefault
public class AbstractParamValueExtractor implements ValueExtractor<AbstractParam<@ExtractedValue ?>> {
    @Override
    public void extractValues(AbstractParam<?> originalValue, ValueReceiver receiver) {
        receiver.value(null, originalValue == null ? null : originalValue.get());
    }
}
