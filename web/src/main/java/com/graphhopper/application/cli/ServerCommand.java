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

import com.graphhopper.application.GraphHopperApplication;
import com.graphhopper.application.GraphHopperServerConfiguration;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Starts the web server and the admin server.
 */
public class ServerCommand extends ConfiguredCommand {

    public ServerCommand() {
        super("server", "Runs the GraphHopper web server");
    }

    @Override
    protected void run(Namespace namespace, GraphHopperServerConfiguration configuration) {
        GraphHopperApplication.start(configuration);
    }
}
