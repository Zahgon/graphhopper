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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.graphhopper.http.MultiExceptionGPXMessageConverter;
import com.graphhopper.http.TypeGPXFilter;
import com.graphhopper.http.errors.GHExceptionHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How requests are mapped to the resources and how parameters and bodies are read and written:
 * <ul>
 *     <li>a path matches with and without trailing slash</li>
 *     <li>request parameters are converted like described in {@link ParamConverters}</li>
 *     <li>errors are written in a media type the resource method produces, GPX only for requests that asked for it</li>
 *     <li>plain text is written as UTF-8 without charset parameter in the content type</li>
 *     <li>XML is read and written with a plain {@link XmlMapper}, without charset parameter in the content type</li>
 *     <li>a JSON request body of only whitespace counts as no body</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class GHWebMvcConfiguration implements WebMvcConfigurer {

    @Override
    @SuppressWarnings("deprecation")
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseTrailingSlashMatch(true);
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.strategies(List.of(new AcceptHeaderStrategy()));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Object produces = request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
                if (produces != null)
                    request.setAttribute(GHExceptionHandler.PRODUCES_ATTRIBUTE, produces);
                return true;
            }
        });
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        ParamConverters.register(registry, (ConversionService) registry);
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.replaceAll(converter -> converter instanceof StringHttpMessageConverter ? new PlainStringHttpMessageConverter()
                : converter instanceof MappingJackson2XmlHttpMessageConverter ? xmlConverter()
                : converter instanceof MappingJackson2HttpMessageConverter json ? new BlankBodyJsonConverter(json.getObjectMapper())
                : converter);
        // JSON stays the default for errors, GPX is only written when asked for, before the XML converter would take it
        int json = 0;
        while (json < converters.size() && !(converters.get(json) instanceof MappingJackson2HttpMessageConverter))
            json++;
        converters.add(Math.min(json + 1, converters.size()), new MultiExceptionGPXMessageConverter());
    }

    private static MappingJackson2XmlHttpMessageConverter xmlConverter() {
        MappingJackson2XmlHttpMessageConverter converter = new MappingJackson2XmlHttpMessageConverter(new XmlMapper());
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_XML, MediaType.TEXT_XML, new MediaType("application", "*+xml")));
        return converter;
    }

    @Bean
    public PlainOptionsFilter plainOptionsFilter() {
        return new PlainOptionsFilter();
    }

    @Bean
    public FilterRegistrationBean<ContentTypeFilter> contentTypeFilter() {
        FilterRegistrationBean<ContentTypeFilter> registration = new FilterRegistrationBean<>(new ContentTypeFilter());
        registration.addServletNames(DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME);
        return registration;
    }

    @Bean(name = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
    public DispatcherServlet dispatcherServlet() {
        DispatcherServlet servlet = new ResourceOptionsDispatcherServlet();
        servlet.setDispatchTraceRequest(false);
        servlet.setPublishEvents(true);
        return servlet;
    }

    /**
     * OPTIONS requests are answered by the resources alone, so an unknown path gets a 404 without Allow header.
     */
    static class ResourceOptionsDispatcherServlet extends DispatcherServlet {
        @Override
        protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            processRequest(request, response);
        }
    }

    @Bean
    public FilterRegistrationBean<DeferredCommitFilter> deferredCommitFilter() {
        FilterRegistrationBean<DeferredCommitFilter> registration = new FilterRegistrationBean<>(new DeferredCommitFilter());
        registration.addServletNames(DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME);
        return registration;
    }

    @Bean
    public JettyCompressionCustomizer jettyCompressionCustomizer() {
        return new JettyCompressionCustomizer();
    }

    @Bean
    public static FirstValueRequestParamResolver.Installer firstValueRequestParamResolverInstaller() {
        return new FirstValueRequestParamResolver.Installer();
    }

    @Bean
    public WebMvcRegistrations ghWebMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new GHRequestMappingHandlerMapping();
            }
        };
    }

    @Bean
    public FilterRegistrationBean<TypeGPXFilter> typeGPXFilter() {
        FilterRegistrationBean<TypeGPXFilter> registration = new FilterRegistrationBean<>(new TypeGPXFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Bean
    public JettyErrorHandlerCustomizer jettyErrorHandlerCustomizer() {
        return new JettyErrorHandlerCustomizer();
    }

    @Bean
    public ParamBindingAdvice paramBindingAdvice() {
        return new ParamBindingAdvice();
    }

    /**
     * Integer, long and double parameters are parsed strictly, see {@link ParamConverters.StrictEditor}.
     */
    @ControllerAdvice
    public static class ParamBindingAdvice {
        @InitBinder
        public void initBinder(WebDataBinder binder) {
            binder.registerCustomEditor(int.class, new ParamConverters.StrictEditor(Integer::valueOf));
            binder.registerCustomEditor(Integer.class, new ParamConverters.StrictEditor(Integer::valueOf));
            binder.registerCustomEditor(long.class, new ParamConverters.StrictEditor(Long::valueOf));
            binder.registerCustomEditor(Long.class, new ParamConverters.StrictEditor(Long::valueOf));
            binder.registerCustomEditor(double.class, new ParamConverters.StrictEditor(Double::valueOf));
            binder.registerCustomEditor(Double.class, new ParamConverters.StrictEditor(Double::valueOf));
        }
    }

    /**
     * Writes strings as UTF-8, without adding a charset parameter to the content type.
     */
    static class PlainStringHttpMessageConverter extends StringHttpMessageConverter {
        PlainStringHttpMessageConverter() {
            super(StandardCharsets.UTF_8);
            setWriteAcceptCharset(false);
        }

        @Override
        protected void addDefaultHeaders(HttpHeaders headers, String s, MediaType type) throws IOException {
            if (headers.getContentType() == null && type != null && type.isConcrete())
                headers.setContentType(type);
            super.addDefaultHeaders(headers, s, type);
        }
    }

    /**
     * Reads a request body without any JSON token (empty or only whitespace) as null, rejects a body with anything but
     * whitespace after the JSON value, and detects the encoding of the body from its bytes, whatever charset the
     * Content-Type names.
     */
    static class BlankBodyJsonConverter extends MappingJackson2HttpMessageConverter {
        BlankBodyJsonConverter(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        protected ObjectReader customizeReader(ObjectReader reader, JavaType javaType) {
            return reader.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        }

        @Override
        protected Charset getCharset(MediaType contentType) {
            return StandardCharsets.UTF_8;
        }

        @Override
        public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
                throws IOException {
            try {
                return super.read(type, contextClass, inputMessage);
            } catch (HttpMessageNotReadableException e) {
                if (e.getCause() instanceof MismatchedInputException cause
                        && cause.getMessage() != null && cause.getMessage().startsWith("No content to map due to end-of-input"))
                    return null;
                throw e;
            }
        }
    }
}
