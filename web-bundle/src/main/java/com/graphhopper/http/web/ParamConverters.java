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
import com.graphhopper.http.errors.ErrorMessage;
import com.graphhopper.http.errors.ParamException;
import com.graphhopper.util.shapes.GHPoint;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.beans.PropertyEditorSupport;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Conversion of request parameter strings into the argument types of the resources:
 * <ul>
 *     <li>numbers are parsed strictly by their valueOf methods, booleans with {@link Boolean#valueOf(String)}</li>
 *     <li>OptionalInt, OptionalLong and OptionalDouble are empty for an empty value</li>
 *     <li>a single value given to a list argument is never split at commas</li>
 *     <li>{@link AbstractParam} types are created with the parameter name, like "query param point"</li>
 *     <li>enums match their constants leniently, see {@link EnumConverter}</li>
 * </ul>
 * A value that cannot be converted makes the request fail with 404, except for {@link AbstractParam}s, which answer
 * with their own 400 message.
 */
public final class ParamConverters {

    private ParamConverters() {
    }

    public static void register(FormatterRegistry registry, ConversionService conversionService) {
        registry.addConverter(String[].class, String.class, values -> values.length == 0 ? null : values[0]);
        registry.addConverter(String.class, Boolean.class, Boolean::valueOf);
        registry.addConverter(String.class, Integer.class, Integer::valueOf);
        registry.addConverter(String.class, Long.class, Long::valueOf);
        registry.addConverter(String.class, Double.class, Double::valueOf);
        registry.addConverter(new OptionalConverter<>(OptionalInt.class, s -> OptionalInt.of(Integer.parseInt(s)), OptionalInt.empty()));
        registry.addConverter(new OptionalConverter<>(OptionalLong.class, s -> OptionalLong.of(Long.parseLong(s)), OptionalLong.empty()));
        registry.addConverter(new OptionalConverter<>(OptionalDouble.class, s -> OptionalDouble.of(Double.parseDouble(s)), OptionalDouble.empty()));
        registry.addConverter(String.class, GHPoint.class, (Converter<String, GHPoint>) s -> s.isEmpty() ? null : GHPoint.fromString(s));
        registry.addConverter(new AbstractParamConverter());
        registry.addConverter(new EnumConverter());
        registry.addConverter(new SingleValueToCollectionConverter(conversionService));
    }

    /**
     * @return a description of the parameter like "query param point" or "path param z", null if unknown
     */
    static String parameterName(TypeDescriptor descriptor) {
        RequestParam requestParam = descriptor.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
            return "query param " + name;
        }
        PathVariable pathVariable = descriptor.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = pathVariable.name().isEmpty() ? pathVariable.value() : pathVariable.name();
            return "path param " + name;
        }
        return null;
    }

    private static final class OptionalConverter<T> implements GenericConverter {
        private final Class<T> type;
        private final Function<String, T> parser;
        private final T empty;

        OptionalConverter(Class<T> type, Function<String, T> parser, T empty) {
            this.type = type;
            this.parser = parser;
            this.empty = empty;
        }

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Collections.singleton(new ConvertiblePair(String.class, type));
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            String value = (String) source;
            if (value == null || value.isEmpty())
                return empty;
            return parser.apply(value);
        }
    }

    private static final class AbstractParamConverter implements ConditionalGenericConverter {
        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Collections.singleton(new ConvertiblePair(String.class, AbstractParam.class));
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            return AbstractParam.class.isAssignableFrom(targetType.getType());
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null)
                return null;
            String name = parameterName(targetType);
            try {
                if (name == null) {
                    Constructor<?> constructor = targetType.getType().getConstructor(String.class);
                    return constructor.newInstance(source);
                }
                Constructor<?> constructor = targetType.getType().getConstructor(String.class, String.class);
                return constructor.newInstance(source, name);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtimeException)
                    throw runtimeException;
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + targetType.getType().getName(), e);
            }
        }
    }

    /**
     * Enum constants match ignoring case, whitespace, and with '-' and '.' standing for '_'. An empty value is no
     * value, an unknown one is answered with 400 "query param type must be one of [json, geojson]".
     */
    private static final class EnumConverter implements ConditionalGenericConverter {
        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Collections.singleton(new ConvertiblePair(String.class, Enum.class));
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            return targetType.getType().isEnum();
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            String value = (String) source;
            if (value == null || value.isEmpty())
                return null;
            Enum<?>[] constants = (Enum<?>[]) targetType.getType().getEnumConstants();
            String text = value.replaceAll("\\s", "").replace('-', '_').replace('.', '_');
            for (Enum<?> constant : constants)
                if (constant.name().equalsIgnoreCase(text))
                    return constant;
            for (Enum<?> constant : constants)
                if (constant.toString().equalsIgnoreCase(text))
                    return constant;
            String names = Arrays.stream(constants).map(Enum::toString).collect(Collectors.joining(", "));
            throw new ParamException(new ErrorMessage(400, parameterName(targetType) + " must be one of [" + names + "]"));
        }
    }

    /**
     * A single parameter value becomes a one element collection, where Spring would split it at commas.
     */
    private static final class SingleValueToCollectionConverter implements ConditionalGenericConverter {
        private final ConversionService conversionService;

        SingleValueToCollectionConverter(ConversionService conversionService) {
            this.conversionService = conversionService;
        }

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Collections.singleton(new ConvertiblePair(String.class, Collection.class));
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            TypeDescriptor elementType = targetType.getElementTypeDescriptor();
            return elementType == null || conversionService.canConvert(sourceType, elementType);
        }

        @Override
        public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
            if (source == null)
                return null;
            TypeDescriptor elementType = targetType.getElementTypeDescriptor();
            Object element = elementType == null ? source : conversionService.convert(source, sourceType, elementType);
            Collection<Object> result = Set.class.isAssignableFrom(targetType.getType()) ? new LinkedHashSet<>() : new ArrayList<>();
            result.add(element);
            return result;
        }
    }

    /**
     * Strict number parsing for single values, so that Spring does not fall back to its lenient number editors
     * (which would accept "0x0e" or " 14") when a value cannot be converted.
     */
    public static final class StrictEditor extends PropertyEditorSupport {
        private final Function<String, Object> parser;

        public StrictEditor(Function<String, Object> parser) {
            this.parser = parser;
        }

        @Override
        public void setAsText(String text) {
            setValue(text == null || text.isEmpty() ? null : parser.apply(text));
        }
    }
}
