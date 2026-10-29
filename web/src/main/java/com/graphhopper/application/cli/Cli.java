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

import com.graphhopper.http.ConfigurationFactory;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.helper.HelpScreenException;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparsers;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line interface: "java -jar graphhopper-web.jar server config.yml" and the other commands.
 */
public class Cli {
    private static final String COMMAND = "command";

    private final String programName;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final PrintStream out;
    private final PrintStream err;

    public Cli(String programName, List<Command> commands, PrintStream out, PrintStream err) {
        this.programName = programName;
        for (Command command : commands)
            this.commands.put(command.getName(), command);
        this.out = out;
        this.err = err;
    }

    /**
     * @return true if the command was run successfully
     */
    public boolean run(String... arguments) {
        ArgumentParser parser = ArgumentParsers.newFor(programName).build().defaultHelp(true);
        Subparsers subparsers = parser.addSubparsers().dest(COMMAND).metavar("{" + String.join(",", commands.keySet()) + "}");
        for (Command command : commands.values())
            command.configure(subparsers.addParser(command.getName()).help(command.getDescription())
                    .description(command.getDescription()).defaultHelp(true));
        Namespace namespace;
        try {
            namespace = parser.parseArgs(arguments);
        } catch (HelpScreenException e) {
            return true;
        } catch (ArgumentParserException e) {
            PrintWriter writer = new PrintWriter(err, true);
            e.getParser().handleError(e, writer);
            writer.flush();
            return false;
        }
        Command command = commands.get(namespace.getString(COMMAND));
        try {
            command.run(namespace);
            return true;
        } catch (ConfigurationFactory.ConfigurationException e) {
            err.println(e.getMessage());
            return false;
        } catch (Throwable t) {
            t.printStackTrace(err);
            return false;
        }
    }
}
