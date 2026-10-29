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
package com.graphhopper.resources;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphhopper.util.Translation;
import com.graphhopper.util.TranslationMap;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

/**
 * @author Peter Karich
 */
@RestController
@RequestMapping(value = "/i18n", produces = MediaType.APPLICATION_JSON_VALUE)
public class I18NResource {

    private final TranslationMap map;

    public I18NResource(TranslationMap map) {
        this.map = map;
    }

    public static class Response {
        public String locale;
        public Map<String, String> en;
        @JsonProperty("default")
        public Map<String, String> defaultTr;
    }

    @GetMapping
    public Response getFromHeader(@RequestHeader(name = "Accept-Language", required = false) String acceptLang) {
        if (acceptLang == null)
            return get("");
        return get(acceptLang.split(",")[0]);
    }

    @GetMapping("/{locale}")
    public Response get(@PathVariable("locale") String locale) {
        Translation tr = map.get(locale);
        Response json = new Response();
        if (tr != null && !Locale.US.equals(tr.getLocale())) {
            json.defaultTr = tr.asMap();
        }
        json.locale = locale;
        json.en = map.get("en").asMap();
        return json;
    }

}
