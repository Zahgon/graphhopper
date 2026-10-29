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
package com.graphhopper.application.cli;

import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.http.ConfigurationFactory;
import com.graphhopper.http.GraphHopperBundle;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.File;

/**
 * A command that reads the configuration file given as "file" argument.
 */
public abstract class ConfiguredCommand extends Command {

    protected ConfiguredCommand(String name, String description) {
        super(name, description);
    }

    @Override
    public void configure(Subparser subparser) {
        addFileArgument(subparser);
    }

    protected Argument addFileArgument(Subparser subparser) {
        return subparser.addArgument("file")
                .nargs("?")
                .help("application configuration file");
    }

    @Override
    public final void run(Namespace namespace) throws Exception {
        String file = namespace.getString("file");
        if (file == null)
            throw new IllegalArgumentException("A configuration file is required");
        GraphHopperServerConfiguration configuration = new ConfigurationFactory<>(GraphHopperServerConfiguration.class,
                GraphHopperBundle.newObjectMapper()).build(new File(file));
        run(namespace, configuration);
    }

    protected abstract void run(Namespace namespace, GraphHopperServerConfiguration configuration) throws Exception;
}
