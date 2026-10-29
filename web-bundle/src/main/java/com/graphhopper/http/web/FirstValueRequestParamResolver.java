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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Resolves {@code @RequestParam} arguments like Spring does, except that
 * <ul>
 *     <li>a parameter given several times binds its first value when the argument is a single value (Spring would
 *     join the values with commas), collection and array arguments still receive every value</li>
 *     <li>an absent collection parameter is an empty collection</li>
 *     <li>an absent {@link AbstractParam} parameter is a parameter object without value</li>
 * </ul>
 */
public class FirstValueRequestParamResolver extends RequestParamMethodArgumentResolver {

    public FirstValueRequestParamResolver(boolean useDefaultResolution) {
        super(useDefaultResolution);
    }

    @Override
    protected Object resolveName(String name, MethodParameter parameter, NativeWebRequest request) throws Exception {
        Object value = super.resolveName(name, parameter, request);
        Class<?> type = parameter.getNestedParameterType();
        boolean annotated = parameter.hasParameterAnnotation(RequestParam.class);
        if (value == null && annotated && Collection.class.isAssignableFrom(type))
            return new String[0];
        if (value == null && annotated && AbstractParam.class.isAssignableFrom(type)) {
            try {
                return type.getConstructor(String.class, String.class).newInstance(null, "query param " + name);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        if (value instanceof String[] values && values.length > 0 && !type.isArray() && !Collection.class.isAssignableFrom(type))
            return values[0];
        return value;
    }

    /**
     * Puts this resolver in place of Spring's own {@link RequestParamMethodArgumentResolver}s.
     */
    public static class Installer implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof RequestMappingHandlerAdapter adapter && adapter.getArgumentResolvers() != null) {
                List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
                // the first default instance is the annotation based one, the last resolves simple types without annotation
                boolean annotated = true;
                for (HandlerMethodArgumentResolver resolver : adapter.getArgumentResolvers()) {
                    if (resolver.getClass() == RequestParamMethodArgumentResolver.class) {
                        resolvers.add(new FirstValueRequestParamResolver(!annotated));
                        annotated = false;
                    } else {
                        resolvers.add(resolver);
                    }
                }
                adapter.setArgumentResolvers(resolvers);
            }
            return bean;
        }
    }
}
