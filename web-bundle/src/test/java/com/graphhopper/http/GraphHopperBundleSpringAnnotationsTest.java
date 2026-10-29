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
 */
package com.graphhopper.http;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration retires DropwizardBundle/HK2 Factories in favour of a Spring @Configuration
 * that exposes GraphHopper and its collaborators as @Bean methods. This verifies the shape so
 * the port cannot silently regress to no-bean configuration.
 */
class GraphHopperBundleSpringAnnotationsTest {

    @Test
    void bundleIsSpringConfiguration() {
        Configuration configuration = GraphHopperBundle.class.getAnnotation(Configuration.class);
        assertNotNull(configuration, "GraphHopperBundle must be a Spring @Configuration");
    }

    @Test
    void bundleExposesGraphHopperBean() {
        Method bean = Arrays.stream(GraphHopperBundle.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .filter(m -> m.getName().equals("graphHopper"))
                .findFirst().orElse(null);
        assertNotNull(bean, "GraphHopperBundle must declare a @Bean graphHopper(...)");
    }

    @Test
    void bundleExposesAtLeastSevenBeans() {
        long beans = Arrays.stream(GraphHopperBundle.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .count();
        assertTrue(beans >= 7,
                "GraphHopperBundle must expose the GraphHopper, TranslationMap and friends as @Bean (saw " + beans + ")");
    }
}
