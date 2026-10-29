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
package com.graphhopper.application.assets;

import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;

/**
 * Serves the files below a classpath directory, e.g. the web application of /com/graphhopper/maps/ under /maps/:
 * <ul>
 *     <li>a directory is answered with its index file, if there is one</li>
 *     <li>ETag and Last-Modified headers, and 304 for a matching If-None-Match or a later If-Modified-Since</li>
 *     <li>byte ranges ("Range: bytes=0-9") are answered with 206</li>
 *     <li>the content type follows the file extension, text types have the default charset</li>
 * </ul>
 */
public class AssetServlet extends HttpServlet {
    private static final Logger LOG = LoggerFactory.getLogger(AssetServlet.class);
    private static final String DEFAULT_CONTENT_TYPE = "text/html";

    private final String resourcePath;
    private final String uriPath;
    private final String indexFile;
    private final Charset defaultCharset;

    /**
     * @param resourcePath   the classpath directory of the files, like "/com/graphhopper/maps/"
     * @param uriPath        the path the servlet is mapped to, like "/maps/"
     * @param indexFile      the file that answers requests for a directory, null for none
     * @param defaultCharset the charset of text files
     */
    public AssetServlet(String resourcePath, String uriPath, String indexFile, Charset defaultCharset) {
        String trimmedPath = trimSlashes(resourcePath);
        this.resourcePath = trimmedPath.isEmpty() ? trimmedPath : trimmedPath + '/';
        String trimmedUri = trimTrailingSlashes(uriPath);
        this.uriPath = trimmedUri.isEmpty() ? "/" : trimmedUri;
        this.indexFile = indexFile;
        this.defaultCharset = defaultCharset;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            StringBuilder builder = new StringBuilder(req.getServletPath());
            if (req.getPathInfo() != null)
                builder.append(req.getPathInfo());
            CachedAsset cachedAsset = loadAsset(builder.toString());
            if (cachedAsset == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (isCachedClientSide(req, cachedAsset)) {
                resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }

            String rangeHeader = req.getHeader("Range");
            int resourceLength = cachedAsset.resource.length;
            List<ByteRange> ranges = List.of();
            boolean usingRanges = false;
            if (rangeHeader != null) {
                String ifRange = req.getHeader("If-Range");
                if (ifRange == null || cachedAsset.eTag.equals(ifRange)) {
                    try {
                        ranges = parseRangeHeader(rangeHeader, resourceLength);
                    } catch (NumberFormatException e) {
                        resp.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                        return;
                    }
                    if (ranges.isEmpty()) {
                        resp.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                        return;
                    }
                    resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    usingRanges = true;
                    List<String> byteRanges = new ArrayList<>();
                    for (ByteRange range : ranges)
                        byteRanges.add(range.toString());
                    resp.addHeader("Content-Range", "bytes " + String.join(",", byteRanges) + "/" + resourceLength);
                }
            }

            resp.setDateHeader("Last-Modified", cachedAsset.lastModifiedTime);
            resp.setHeader("ETag", cachedAsset.eTag);

            String contentType = DEFAULT_CONTENT_TYPE;
            String charset = defaultCharset == null ? null : defaultCharset.toString();
            String mimeTypeOfExtension = req.getServletContext().getMimeType(req.getRequestURI());
            if (mimeTypeOfExtension != null) {
                String type = mimeTypeOfExtension.split(";")[0].trim().toLowerCase(Locale.ROOT);
                if (type.contains("/")) {
                    contentType = type;
                    if (!type.startsWith("text/"))
                        charset = null;
                }
            }
            if (contentType.startsWith("video/") || contentType.startsWith("audio/") || usingRanges)
                resp.addHeader("Accept-Ranges", "bytes");

            resp.setContentType(contentType);
            if (charset != null)
                resp.setCharacterEncoding(charset);

            try (ServletOutputStream output = resp.getOutputStream()) {
                if (usingRanges) {
                    for (ByteRange range : ranges)
                        output.write(cachedAsset.resource, range.start, range.end - range.start + 1);
                } else {
                    output.write(cachedAsset.resource);
                }
            }
        } catch (IllegalArgumentException | URISyntaxException noAsset) {
            // loadAsset reports a path outside the servlet or without a classpath resource this way
            LOG.debug("asset lookup rejected: {}", noAsset.toString());
            notFound(resp);
        }
    }

    /**
     * A missing asset file is an empty 404 without any header set so far, not even the CORS headers or "Vary"; a
     * directory without index file gets the error page instead.
     */
    private static void notFound(HttpServletResponse resp) {
        resp.reset();
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    private CachedAsset loadAsset(String key) throws URISyntaxException, IOException {
        if (!key.startsWith(uriPath))
            throw new IllegalArgumentException("Not below " + uriPath + ": " + key);
        String requestedResourcePath = trimSlashes(key.substring(uriPath.length()));
        String absoluteRequestedResourcePath = trimSlashes(this.resourcePath + requestedResourcePath);

        URL requestedResourceURL = getResourceUrl(absoluteRequestedResourcePath);
        if (isDirectory(requestedResourceURL)) {
            if (indexFile == null)
                return null;
            requestedResourceURL = getResourceUrl(absoluteRequestedResourcePath + '/' + indexFile);
        }

        long lastModified = getLastModified(requestedResourceURL);
        if (lastModified < 1)
            lastModified = System.currentTimeMillis();
        // zero out the millis since the date we get back from If-Modified-Since will not have them
        lastModified = (lastModified / 1000) * 1000;
        return new CachedAsset(readResource(requestedResourceURL), lastModified);
    }

    private URL getResourceUrl(String absoluteRequestedResourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL url = classLoader == null ? null : classLoader.getResource(absoluteRequestedResourcePath);
        if (url == null)
            url = AssetServlet.class.getClassLoader().getResource(absoluteRequestedResourcePath);
        if (url == null)
            throw new IllegalArgumentException("resource " + absoluteRequestedResourcePath + " not found.");
        return url;
    }

    private static byte[] readResource(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static boolean isDirectory(URL resourceURL) throws URISyntaxException {
        String protocol = resourceURL.getProtocol();
        if (protocol.equals("file"))
            return new File(resourceURL.toURI()).isDirectory();
        if (protocol.equals("jar")) {
            try {
                JarURLConnection connection = (JarURLConnection) resourceURL.openConnection();
                JarEntry entry = connection.getJarEntry();
                if (entry == null)
                    return false;
                // the jar may have no entry for the directory itself, but one for the name with a slash
                return entry.isDirectory() || connection.getJarFile().getEntry(entry.getName() + "/") != null;
            } catch (IOException e) {
                LOG.debug("jar entry probe failed for {}: {}", resourceURL, e.toString());
                return false;
            }
        }
        return false;
    }

    private static long getLastModified(URL resourceURL) {
        String protocol = resourceURL.getProtocol();
        try {
            if (protocol.equals("file"))
                return new File(resourceURL.toURI()).lastModified();
            if (protocol.equals("jar")) {
                JarURLConnection connection = (JarURLConnection) resourceURL.openConnection();
                connection.setUseCaches(false);
                JarEntry entry = connection.getJarEntry();
                return entry == null ? 0 : entry.getTime();
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            LOG.debug("resource length probe failed: {}", e.toString());
            return 0;
        }
        return 0;
    }

    private static boolean isCachedClientSide(HttpServletRequest req, CachedAsset cachedAsset) {
        return cachedAsset.eTag.equals(req.getHeader("If-None-Match")) ||
                (req.getDateHeader("If-Modified-Since") >= cachedAsset.lastModifiedTime);
    }

    private static List<ByteRange> parseRangeHeader(String rangeHeader, int resourceLength) {
        List<ByteRange> result = new ArrayList<>();
        if (rangeHeader.contains("=")) {
            String[] parts = rangeHeader.split("=", -1);
            if (parts.length > 1) {
                for (String range : parts[1].split(",", -1))
                    result.add(ByteRange.parse(range.trim(), resourceLength));
            }
        }
        return result;
    }

    private static String trimSlashes(String value) {
        return trimTrailingSlashes(value.replaceAll("^/+", ""));
    }

    private static String trimTrailingSlashes(String value) {
        return value.replaceAll("/+$", "");
    }

    private static final class CachedAsset {
        private final byte[] resource;
        private final String eTag;
        private final long lastModifiedTime;

        private CachedAsset(byte[] resource, long lastModifiedTime) {
            this.resource = resource;
            this.eTag = '"' + Hashing.murmur3_128().hashBytes(resource).toString() + '"';
            this.lastModifiedTime = lastModifiedTime;
        }
    }

    /**
     * A range of bytes of a "Range: bytes=..." header, with inclusive start and end.
     */
    static final class ByteRange {
        private final int start;
        private final int end;

        ByteRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        static ByteRange parse(String byteRange, int resourceLength) {
            // missing separator
            if (!byteRange.contains("-"))
                return new ByteRange(Integer.parseInt(byteRange), resourceLength - 1);
            // negative range
            if (byteRange.indexOf('-') == 0)
                return new ByteRange(resourceLength + Integer.parseInt(byteRange), resourceLength - 1);
            List<String> parts = new ArrayList<>();
            for (String part : byteRange.split("-"))
                if (!part.trim().isEmpty())
                    parts.add(part.trim());
            int start = Integer.parseInt(parts.get(0));
            if (parts.size() == 2) {
                int end = Integer.parseInt(parts.get(1));
                if (end > resourceLength)
                    end = resourceLength - 1;
                return new ByteRange(start, end);
            }
            return new ByteRange(start, resourceLength - 1);
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }
}
