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
package com.graphhopper.http.admin;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;

/**
 * Writes a dump of all threads as plain text.
 */
class ThreadDumpServlet extends HttpServlet {
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        boolean includeMonitors = getParam(req.getParameter("monitors"), true);
        boolean includeSynchronizers = getParam(req.getParameter("synchronizers"), true);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        resp.setHeader("Cache-Control", AdminServlet.CACHE_CONTROL);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(resp.getOutputStream(), StandardCharsets.UTF_8));
        dump(writer, includeMonitors, includeSynchronizers);
        writer.flush();
    }

    private static boolean getParam(String value, boolean defaultValue) {
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private void dump(PrintWriter writer, boolean lockedMonitors, boolean lockedSynchronizers) {
        ThreadInfo[] threads = threadMXBean.dumpAllThreads(lockedMonitors, lockedSynchronizers);
        for (int ti = threads.length - 1; ti >= 0; ti--) {
            ThreadInfo t = threads[ti];
            writer.printf("\"%s\" id=%d state=%s", t.getThreadName(), t.getThreadId(), t.getThreadState());
            LockInfo lock = t.getLockInfo();
            if (lock != null && t.getThreadState() != Thread.State.BLOCKED) {
                writer.printf("%n    - waiting on <0x%08x> (a %s)", lock.getIdentityHashCode(), lock.getClassName());
                writer.printf("%n    - locked <0x%08x> (a %s)", lock.getIdentityHashCode(), lock.getClassName());
            } else if (lock != null) {
                writer.printf("%n    - waiting to lock <0x%08x> (a %s)", lock.getIdentityHashCode(), lock.getClassName());
            }
            if (t.isSuspended())
                writer.print(" (suspended)");
            if (t.isInNative())
                writer.print(" (running in native)");
            writer.println();
            if (t.getLockOwnerName() != null)
                writer.printf("     owned by %s id=%d%n", t.getLockOwnerName(), t.getLockOwnerId());

            StackTraceElement[] elements = t.getStackTrace();
            MonitorInfo[] monitors = t.getLockedMonitors();
            for (int i = 0; i < elements.length; i++) {
                writer.printf("    at %s%n", elements[i]);
                for (int j = 1; j < monitors.length; j++) {
                    if (monitors[j].getLockedStackDepth() == i)
                        writer.printf("      - locked %s%n", monitors[j]);
                }
            }
            writer.println();

            LockInfo[] locks = t.getLockedSynchronizers();
            if (locks.length > 0) {
                writer.printf("    Locked synchronizers: count = %d%n", locks.length);
                for (LockInfo l : locks)
                    writer.printf("      - %s%n", l);
                writer.println();
            }
        }
        writer.println();
    }
}
