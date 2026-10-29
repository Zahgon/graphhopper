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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the configuration of the application from a YAML file. System properties starting with "dw." override
 * values of the file, e.g. -Ddw.graphhopper.datareader.file=some.pbf:
 * <ul>
 *     <li>the property name is split at dots that are not escaped with a backslash</li>
 *     <li>where an object already has a value for the whole remaining name (like the "datareader.file" key of the
 *     graphhopper object), that value is replaced</li>
 *     <li>otherwise each part names a field, missing objects are created, and "name[2]" addresses an array element</li>
 *     <li>a value for an existing array is split at commas that are not escaped with a backslash</li>
 * </ul>
 * Unknown fields of the configuration class are an error, and so are violated constraints.
 *
 * @param <T> the configuration class
 */
public class ConfigurationFactory<T> {
    public static final String PROPERTY_PREFIX = "dw.";
    private static final Pattern UNESCAPED_DOT = Pattern.compile("(?<!\\\\)\\.");
    private static final Pattern UNESCAPED_COMMA = Pattern.compile("(?<!\\\\),");
    private static final Pattern ESCAPED_COMMA = Pattern.compile("\\\\,");

    private final Class<T> klass;
    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;

    public ConfigurationFactory(Class<T> klass, ObjectMapper mapper) {
        this.klass = klass;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public T build(File file) throws IOException, ConfigurationException {
        JsonNode node;
        try {
            node = yamlMapper.readTree(file);
        } catch (JsonProcessingException e) {
            throw new ConfigurationException(file.getPath(), List.of("Malformed YAML: " + e.getOriginalMessage()));
        }
        return build(node == null || node.isMissingNode() || node.isNull() ? JsonNodeFactory.instance.objectNode() : node, file.getPath());
    }

    public T build(JsonNode node, String path) throws ConfigurationException {
        for (Map.Entry<Object, Object> property : System.getProperties().entrySet()) {
            String name = property.getKey().toString();
            if (name.startsWith(PROPERTY_PREFIX))
                addOverride(node, name.substring(PROPERTY_PREFIX.length()), property.getValue().toString());
        }
        T configuration;
        try {
            configuration = mapper.readValue(mapper.treeAsTokens(node), klass);
        } catch (IOException e) {
            String message = e instanceof JsonProcessingException jsonException ? jsonException.getOriginalMessage() : e.getMessage();
            throw new ConfigurationException(path, List.of(message));
        }
        validate(path, configuration);
        return configuration;
    }

    private void validate(String path, T configuration) throws ConfigurationException {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<T>> violations = validator.validate(configuration);
            if (!violations.isEmpty()) {
                List<String> errors = new ArrayList<>();
                for (ConstraintViolation<T> violation : violations)
                    errors.add(violation.getPropertyPath() + " " + violation.getMessage());
                Collections.sort(errors);
                throw new ConfigurationException(path, errors);
            }
        }
    }

    static void addOverride(JsonNode root, String name, String value) {
        JsonNode node = root;
        String[] parts = UNESCAPED_DOT.split(name);
        for (int i = 0; i < parts.length; i++)
            parts[i] = parts[i].trim().replace("\\.", ".");
        for (int i = 0; i < parts.length; i++) {
            String key = parts[i];
            if (!(node instanceof ObjectNode obj))
                throw new IllegalArgumentException("Unable to override " + name + "; it's not a valid path.");
            String remainingPath = String.join(".", Arrays.copyOfRange(parts, i, parts.length));
            if (obj.has(remainingPath) && !remainingPath.equals(key) && obj.get(remainingPath).isValueNode()) {
                obj.put(remainingPath, value);
                return;
            }
            boolean moreParts = i < parts.length - 1;
            if (key.matches(".+\\[\\d+]$")) {
                int s = key.indexOf('[');
                int index = Integer.parseInt(key.substring(s + 1, key.length() - 1));
                JsonNode child = obj.get(key.substring(0, s));
                if (child == null)
                    throw new IllegalArgumentException("Unable to override " + name + "; node with index not found.");
                if (!child.isArray())
                    throw new IllegalArgumentException("Unable to override " + name + "; node with index is not an array.");
                if (index >= child.size())
                    throw new ArrayIndexOutOfBoundsException("Unable to override " + name + "; index is greater than size of array.");
                if (moreParts) {
                    node = child.get(index);
                    continue;
                }
                ((ArrayNode) child).set(index, TextNode.valueOf(value));
                return;
            } else if (moreParts) {
                JsonNode child = obj.get(key);
                if (child == null) {
                    obj.set(key, obj.objectNode());
                    child = obj.get(key);
                }
                if (child.isArray())
                    throw new IllegalArgumentException("Unable to override " + name + "; target is an array but no index specified");
                node = child;
            }
            if (!moreParts) {
                JsonNode existing = obj.get(key);
                if (existing != null && existing.isArray()) {
                    ArrayNode array = (ArrayNode) existing;
                    array.removeAll();
                    for (String element : UNESCAPED_COMMA.split(value, -1))
                        array.add(ESCAPED_COMMA.matcher(element.trim()).replaceAll(","));
                } else {
                    obj.put(key, value);
                }
            }
        }
    }

    /**
     * A configuration file that cannot be read, bound or validated.
     */
    public static class ConfigurationException extends Exception {
        private final List<String> errors;

        public ConfigurationException(String path, List<String> errors) {
            super(path + " has an error:" + System.lineSeparator() + "  * " + String.join(System.lineSeparator() + "  * ", errors));
            this.errors = errors;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
