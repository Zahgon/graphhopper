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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.graphhopper.http.JsonErrorEntity;
import com.graphhopper.http.MultiExceptionGPXMessageConverter;
import com.graphhopper.http.web.AcceptHeaderStrategy;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.util.Helper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns the exceptions of request processing into the answers of the service:
 * <ul>
 *     <li>{@link IllegalArgumentException} and {@link MultiException}: 400 with the list of errors, written in the
 *     negotiated format (JSON, or GPX for requests that asked for GPX)</li>
 *     <li>parameter values that cannot be converted: 404, or 400 for parameter types that explain the problem</li>
 *     <li>constraint violations: 400 for parameters, 422 for request bodies, {"message":"...","hints":[...]}</li>
 *     <li>unknown paths, methods and media types: {"code":404,"message":"HTTP 404 Not Found"} and the like</li>
 *     <li>anything else: 500 with an id that is also written to the log</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GHExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GHExceptionHandler.class);

    /**
     * The request attribute with the media types the matched resource method produces.
     */
    public static final String PRODUCES_ATTRIBUTE = GHExceptionHandler.class.getName() + ".produces";
    private static final List<MediaType> ERROR_LIST_TYPES = List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML,
            MediaType.TEXT_XML, new MediaType("application", "*+xml"));

    @ExceptionHandler(MultiException.class)
    public ResponseEntity<MultiException> handleMultiException(MultiException e, HttpServletRequest request,
                                                               HttpServletResponse response) throws IOException {
        logger.info("bad request: " + (Helper.isEmpty(e.getMessage())
                ? (e.getErrors().isEmpty() ? "unknown reason" : e.getErrors().toString())
                : e.getErrors()));
        return badRequest(e, request, response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<MultiException> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request,
                                                                HttpServletResponse response) throws IOException {
        logger.info("bad request: " + (Helper.isEmpty(e.getMessage()) ? "unknown reason" : e.getMessage()), e);
        return badRequest(new MultiException(e), request, response);
    }

    /**
     * The list of errors is written in the first media type the resource method produces and the client accepts that
     * has a writer for it (JSON, XML or GPX), with the parameters of the accepted type like "charset=utf-8". If there
     * is none, the request fails with a plain 500 error.
     */
    private static ResponseEntity<MultiException> badRequest(MultiException e, HttpServletRequest request,
                                                             HttpServletResponse response) throws IOException {
        @SuppressWarnings("unchecked")
        Set<MediaType> produces = (Set<MediaType>) request.getAttribute(PRODUCES_ATTRIBUTE);
        if (produces == null || produces.isEmpty())
            return ResponseEntity.badRequest().body(e);
        List<MediaType> accepted = new ArrayList<>();
        Enumeration<String> values = request.getHeaders(HttpHeaders.ACCEPT);
        while (values != null && values.hasMoreElements())
            accepted.addAll(AcceptHeaderStrategy.parse(values.nextElement()));
        if (accepted.isEmpty())
            accepted.add(MediaType.ALL);
        MimeTypeUtils.sortBySpecificity(accepted);
        for (MediaType accept : accepted) {
            for (MediaType produced : produces) {
                if (!accept.isCompatibleWith(produced))
                    continue;
                MediaType chosen = accept.isConcrete() ? accept.removeQualityValue() : produced;
                MediaType type = new MediaType(chosen.getType(), chosen.getSubtype());
                if (MultiExceptionGPXMessageConverter.GPX.equals(type) || ERROR_LIST_TYPES.stream().anyMatch(writable -> writable.includes(type)))
                    return ResponseEntity.badRequest().contentType(chosen).body(e);
            }
        }
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Request failed.");
        return null;
    }

    @ExceptionHandler(ParamException.class)
    public ResponseEntity<ErrorMessage> handleParamException(ParamException e) {
        return json(e.getErrorMessage().getCode(), e.getErrorMessage());
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorMessage> handleTypeMismatch(TypeMismatchException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ParamException paramException)
                return handleParamException(paramException);
        }
        logger.debug("cannot convert parameter", e);
        return status(404);
    }

    @ExceptionHandler(AcceptHeaderStrategy.MalformedAcceptHeaderException.class)
    public ResponseEntity<Void> handleMalformedAccept(AcceptHeaderStrategy.MalformedAcceptHeaderException e,
                                                      HttpServletResponse response) throws IOException {
        logger.debug("malformed Accept header: " + e.getMessage());
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        return null;
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ErrorMessage> handleMissingPathVariable(MissingPathVariableException e) {
        return status(404);
    }

    @ExceptionHandler(HttpStatusException.class)
    public ResponseEntity<ErrorMessage> handleHttpStatus(HttpStatusException e) {
        return status(e.getStatus());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorMessage> handleResponseStatus(ResponseStatusException e) {
        return status(e.getStatusCode().value());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<JsonErrorEntity> handleMethodValidation(HandlerMethodValidationException e) {
        List<String> errors = new ArrayList<>();
        boolean body = false;
        for (var result : e.getParameterValidationResults()) {
            MethodParameter parameter = result.getMethodParameter();
            body |= parameter.hasParameterAnnotation(RequestBody.class);
            String name = describe(parameter);
            for (MessageSourceResolvable error : result.getResolvableErrors())
                errors.add(name + " " + error.getDefaultMessage());
        }
        if (errors.isEmpty())
            errors.add(e.getMessage());
        return violations(body ? 422 : 400, errors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<JsonErrorEntity> handleBodyValidation(MethodArgumentNotValidException e) {
        List<String> errors = new ArrayList<>();
        for (FieldError error : e.getBindingResult().getFieldErrors())
            errors.add(error.getField() + " " + error.getDefaultMessage());
        e.getBindingResult().getGlobalErrors().forEach(error -> errors.add(error.getDefaultMessage()));
        return violations(422, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<JsonErrorEntity> handleConstraintViolation(ConstraintViolationException e) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations())
            errors.add(violation.getPropertyPath() + " " + violation.getMessage());
        return violations(422, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorMessage> handleNotReadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidDefinitionException definitionException)
            return internalError(definitionException);
        if (e.getCause() == null || e.getCause() instanceof JsonProcessingException) {
            logger.debug("Unable to process JSON", e);
            return json(400, new ErrorMessage(400, "Unable to process JSON"));
        }
        return internalError(e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorMessage> handleNotSupported(HttpMediaTypeNotSupportedException e) {
        return status(415);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorMessage> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return status(406);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorMessage> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, allowHeader(e.getSupportedHttpMethods()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(ErrorMessage.forStatus(405), headers, 405);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorMessage> handleNotFound(Exception e) {
        return status(404);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorMessage> handleOther(Exception e) {
        return internalError(e);
    }

    /**
     * @return the methods of a resource like "POST,GET,OPTIONS": the method that was declared last comes first and
     * OPTIONS is always allowed
     */
    public static String allowHeader(Collection<HttpMethod> methods) {
        List<String> names = new ArrayList<>();
        if (methods != null)
            for (HttpMethod method : methods)
                if (!method.equals(HttpMethod.HEAD) && !method.equals(HttpMethod.OPTIONS))
                    names.add(method.name());
        names.sort(Comparator.comparing(name -> name.equals("GET") ? 1 : 0));
        names.add("OPTIONS");
        return String.join(",", names);
    }

    static ResponseEntity<ErrorMessage> internalError(Throwable e) {
        long id = ThreadLocalRandom.current().nextLong();
        String message = String.format("There was an error processing your request. It has been logged (ID %016x).", id);
        logger.error(String.format("Error handling a request: %016x", id), e);
        return json(500, new ErrorMessage(500, message));
    }

    static ResponseEntity<ErrorMessage> status(int code) {
        return json(code, ErrorMessage.forStatus(code));
    }

    static <T> ResponseEntity<T> json(int code, T body) {
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<JsonErrorEntity> violations(int code, List<String> errors) {
        logger.debug("constraint violations: " + errors);
        return json(code, new JsonErrorEntity(errors));
    }

    private static String describe(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null)
            return "query param " + (requestParam.name().isEmpty() ? parameter.getParameterName() : requestParam.name());
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null)
            return "path param " + (pathVariable.name().isEmpty() ? parameter.getParameterName() : pathVariable.name());
        return "The request body";
    }
}
