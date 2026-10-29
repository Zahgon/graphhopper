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
package com.graphhopper.application.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A request body with its media type.
 */
public final class Entity {
    private final String mediaType;
    private final byte[] content;

    private Entity(String mediaType, byte[] content) {
        this.mediaType = mediaType;
        this.content = content;
    }

    public static Entity json(String json) {
        return new Entity("application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static Entity xml(String xml) {
        return new Entity("application/xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    public static Entity xml(InputStream xml) {
        try (InputStream in = xml) {
            return new Entity("application/xml", in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String getMediaType() {
        return mediaType;
    }

    public byte[] getContent() {
        return content;
    }
}
