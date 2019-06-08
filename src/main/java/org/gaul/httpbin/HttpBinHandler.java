/*
 * Copyright 2018 Andrew Gaul <andrew@gaul.org>
 * Copyright 2015-2016 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.httpbin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpBinHandler extends AbstractHandler {
    private static final Logger logger = LoggerFactory.getLogger(
            HttpBinHandler.class);
    private static final int MAX_DELAY_MS = 10 * 1000;

    @Override
    public void handle(String target, Request baseRequest,
            HttpServletRequest request, HttpServletResponse servletResponse)
            throws IOException {
        logger.trace("request: {}", request);
        for (String headerName : Collections.list(
                request.getHeaderNames())) {
            logger.trace("header: {}: {}", headerName,
                    request.getHeader(headerName));
        }
        try (InputStream is = request.getInputStream();
             OutputStream os = servletResponse.getOutputStream()) {
            handleHelper(baseRequest, request, servletResponse, is, os);
        }
    }

    private void handleHelper(Request baseRequest, HttpServletRequest request,
            HttpServletResponse servletResponse, InputStream is,
            OutputStream os) throws IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        try {
            if (uri.equals("/")) {
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.addHeader("Content-Type",
                        "text/html; charset=utf-8");
                copyResource(servletResponse, "/home.html");
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/status/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                int status = Integer.parseInt(uri.substring(
                        "/status/".length()));
                servletResponse.setStatus(status);
                if (status >= 300 && status < 400) {
                    servletResponse.setHeader("Location", "/redirect/1");
                }
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/headers")) {
                JSONObject headers = new JSONObject();
                for (String headerName : Collections.list(
                        request.getHeaderNames())) {
                    headers.put(headerName, request.getHeader(headerName));
                }

                JSONObject response = new JSONObject();
                response.put("headers", headers);
                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/ip")) {
                JSONObject response = new JSONObject();
                response.put("origin", request.getRemoteAddr());
                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/user-agent")) {
                JSONObject response = new JSONObject();
                response.put("user-agent", request.getHeader("User-Agent"));
                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/get")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject response = new JSONObject();
                response.put("args", mapParametersToJSON(request));
                response.put("headers", mapHeadersToJSON(request));
                response.put("origin", request.getRemoteAddr());
                response.put("url", getFullURL(request));

                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/cache")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                if (request.getHeader("If-Modified-Since") != null ||
                        request.getHeader("If-None-Match") != null) {
                    servletResponse.setStatus(
                            HttpServletResponse.SC_NOT_MODIFIED);
                    baseRequest.setHandled(true);
                    return;
                }

                JSONObject response = new JSONObject();
                response.put("args", mapParametersToJSON(request));
                response.put("headers", mapHeadersToJSON(request));
                response.put("origin", request.getRemoteAddr());
                response.put("url", getFullURL(request));

                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.startsWith("/cache/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int seconds = Integer.parseInt(uri.substring(
                        "/cache/".length()));

                JSONObject response = new JSONObject();
                response.put("args", mapParametersToJSON(request));
                response.put("headers", mapHeadersToJSON(request));
                response.put("origin", request.getRemoteAddr());
                response.put("url", getFullURL(request));

                servletResponse.setHeader("Cache-Control",
                        "public, max-age=" + seconds);
                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.startsWith("/delay/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int delayMs = (int) (1000 * Double.parseDouble(uri.substring(
                        "/delay/".length())));
                try {
                    Thread.sleep(Math.min(delayMs, MAX_DELAY_MS));
                } catch (InterruptedException ie) {
                    // ignore
                }

                JSONObject response = new JSONObject();
                response.put("args", mapParametersToJSON(request));
                response.put("headers", mapHeadersToJSON(request));
                response.put("origin", request.getRemoteAddr());
                response.put("url", getFullURL(request));

                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/drip")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                long durationMs = (long) (1000 * Utils.getDoubleParameter(
                        request, "duration", 0.0));
                int numBytes = Utils.getIntParameter(request, "numbytes", 10);
                int code = Utils.getIntParameter(request, "code", 200);
                int delay = Utils.getIntParameter(request, "delay", 0);

                servletResponse.setStatus(code);
                Utils.sleepUninterruptibly(delay, TimeUnit.SECONDS);

                for (int i = 0; i < numBytes; ++i) {
                    Utils.sleepUninterruptibly(durationMs / numBytes,
                            TimeUnit.MILLISECONDS);
                    os.write('*');
                }

                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.startsWith("/stream/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int responses = Integer.parseInt(uri.substring(
                        "/stream/".length()));

                servletResponse.setContentType("application/json");
                servletResponse.setStatus(HttpServletResponse.SC_OK);

                for (int i = 0; i < responses; ++i) {
                    Utils.sleepUninterruptibly(1, TimeUnit.SECONDS);

                    JSONObject response = new JSONObject();
                    response.put("args", mapParametersToJSON(request));
                    response.put("headers", mapHeadersToJSON(request));
                    response.put("origin", request.getRemoteAddr());
                    response.put("url", getFullURL(request));
                    response.put("id", i);

                    byte[] body = response.toString().getBytes(
                            StandardCharsets.UTF_8);
                    os.write(body);
                    os.write('\n');
                    os.flush();
                }

                baseRequest.setHandled(true);
                return;
            } else if ((method.equals("POST") && uri.equals("/post")) ||
                    (method.equals("PUT") && uri.equals("/put"))) {
                JSONObject response = new JSONObject();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Utils.copy(is, baos);
                response.put("data", new String(
                        baos.toByteArray(), StandardCharsets.UTF_8));

                response.put("args", mapParametersToJSON(request));
                response.put("headers", mapHeadersToJSON(request));
                response.put("origin", request.getRemoteAddr());
                response.put("url", getFullURL(request));

                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (uri.equals("/redirect-to")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                redirectTo(servletResponse, request.getParameter("url"));
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/redirect/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        "/redirect/".length())) - 1;
                if (count > 0) {
                    redirectTo(servletResponse, "/redirect/" + count);
                } else {
                    redirectTo(servletResponse, "/get");
                }

                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/absolute-redirect/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        "/absolute-redirect/".length())) - 1;
                StringBuffer path = request.getRequestURL();
                path.setLength(path.length() - uri.length());
                if (count > 0) {
                    path.append("/absolute-redirect/")
                            .append(count);
                    redirectTo(servletResponse, path.toString());
                } else {
                    path.append("/get");
                    redirectTo(servletResponse, path.toString());
                }

                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") &&
                    uri.equals("/response-headers")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                for (String paramName : Collections.list(
                        request.getParameterNames())) {
                    servletResponse.addHeader(paramName, request.getParameter(
                            paramName));
                }
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                return;
            } else if (uri.equals("/cookies")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject cookies = new JSONObject();

                if (request.getCookies() != null) {
                    for (Cookie cookie : request.getCookies()) {
                        cookies.put(cookie.getName(), cookie.getValue());
                    }
                }

                JSONObject response = new JSONObject();
                response.put("cookies", cookies);

                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/cookies/set")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (Map.Entry<String, String[]> entry :
                        request.getParameterMap().entrySet()) {
                    for (String value : entry.getValue()) {
                        servletResponse.addHeader("Set-Cookie", String.format(
                                "%s=%s; Path=/", entry.getKey(), value));
                    }
                }

                servletResponse.setHeader("Location", "/cookies");
                servletResponse.setStatus(
                        HttpServletResponse.SC_MOVED_TEMPORARILY);
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/cookies/delete")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (Map.Entry<String, String[]> entry :
                        request.getParameterMap().entrySet()) {
                    servletResponse.addHeader("Set-Cookie", String.format(
                            "%s=; Path=/", entry.getKey()));
                }

                servletResponse.setHeader("Location", "/cookies");
                servletResponse.setStatus(
                        HttpServletResponse.SC_MOVED_TEMPORARILY);
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/basic-auth/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, servletResponse, os,
                        uri.substring("/basic-auth/".length()),
                        HttpServletResponse.SC_UNAUTHORIZED);
                baseRequest.setHandled(true);
                return;
            } else if (uri.startsWith("/hidden-basic-auth/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, servletResponse, os,
                        uri.substring("/hidden-basic-auth/".length()),
                        HttpServletResponse.SC_NOT_FOUND);
                baseRequest.setHandled(true);
                return;
            } else if (uri.equals("/anything")) {
                servletResponse.setStatus(HttpServletResponse.SC_OK);

                final JSONObject response = new JSONObject();

                // Method
                response.put("method", method);

                response.put("headers", mapHeadersToJSON(request));

                // Body data
                final ByteArrayOutputStream data = new ByteArrayOutputStream();
                Utils.copy(is, data);

                response.put("data", data.toString("UTF-8"));
                respondJSON(servletResponse, os, response);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.startsWith("/bytes/")) {
                long length = Long.parseLong(uri.substring(
                        "/bytes/".length()));
                int seed = Utils.getIntParameter(request, "seed", -1);
                Random random = seed != -1 ?  new Random(seed) : new Random();

                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.setContentLengthLong(length);
                byte[] buffer = new byte[4096];
                for (long i = 0; i < length;) {
                    int count = (int) Math.min(buffer.length, length - i);
                    random.nextBytes(buffer);
                    os.write(buffer, 0, count);
                    i += count;
                }
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/image/jpeg")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.addHeader("Content-Type", "image/jpeg");
                copyResource(servletResponse, "/image.jpg");
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/image/png")) {
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.addHeader("Content-Type", "image/png");
                copyResource(servletResponse, "/image.png");
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/html")) {
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.addHeader("Content-Type",
                        "text/html; charset=utf-8");
                copyResource(servletResponse, "/text.html");
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/xml")) {
                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.addHeader("Content-Type", "application/xml");
                copyResource(servletResponse, "/text.xml");
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/robots.txt")) {
                byte[] output = "User-agent: *\nDisallow: /deny\n".getBytes(
                        StandardCharsets.UTF_8);

                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.setContentType("text/plain");
                os.write(output);
                baseRequest.setHandled(true);
                return;
            } else if (method.equals("GET") && uri.equals("/deny")) {
                byte[] output = (
                        "    .-''''''-." +
                        "  .' _      _ '." +
                        " /   O      O   \"" +
                        ":                :" +
                        "|                |" +
                        ":       __       :" +
                        " \\  .-\"'  '\"-.  /" +
                        "  '.          .'" +
                        "     '-......-'" +
                        "YOU SHOULDN'T BE HERE").getBytes(
                                StandardCharsets.UTF_8);

                servletResponse.setStatus(HttpServletResponse.SC_OK);
                servletResponse.setContentType("text/plain");
                os.write(output);
                baseRequest.setHandled(true);
                return;
            }
            servletResponse.setStatus(501);
            baseRequest.setHandled(true);
        } catch (JSONException e) {
            servletResponse.setStatus(500);
            baseRequest.setHandled(true);
        }
    }

    private static void respondJSON(HttpServletResponse response,
            OutputStream os, JSONObject obj) throws IOException {
        byte[] body = obj.toString(/*indent=*/ 2).getBytes(
                StandardCharsets.UTF_8);

        response.setContentLength(body.length);
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        os.write(body);
        os.flush();
    }

    private static void redirectTo(HttpServletResponse response,
            String location) {
        response.setHeader("Location", location);
        response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
    }

    private void copyResource(HttpServletResponse response, String resource)
            throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            long length = Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
            response.setContentLength((int) length);
        }
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            Utils.copy(is, response.getOutputStream());
        }
    }

    private static JSONObject mapHeadersToJSON(HttpServletRequest request) {
        JSONObject headers = new JSONObject();

        for (String name : Collections.list(request.getHeaderNames())) {
            List<String> values = Collections.list(request.getHeaders(name));
            if (values.size() == 1) {
                headers.put(name, values.get(0));
            } else {
                headers.put(name, new JSONArray(values));
            }
        }

        return headers;
    }

    private static JSONObject mapParametersToJSON(HttpServletRequest request) {
        JSONObject headers = new JSONObject();

        for (String name : Collections.list(request.getParameterNames())) {
            String[] values = request.getParameterValues(name);
            if (values.length == 1) {
                headers.put(name, values[0]);
            } else {
                headers.put(name, new JSONArray(values));
            }
        }

        return headers;
    }

    private static String getFullURL(HttpServletRequest request) {
        StringBuilder requestURL = new StringBuilder(
                request.getRequestURL().toString());
        String queryString = request.getQueryString();
        if (queryString == null) {
            return requestURL.toString();
        } else {
            return requestURL.append('?').append(queryString).toString();
        }
    }

    private static void handleBasicAuth(HttpServletRequest request,
            HttpServletResponse servletResponse, OutputStream os,
            String suffix, int failureStatus) throws IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            servletResponse.setStatus(failureStatus);
            return;
        }

        byte[] bytes = Base64.decodeBase64(
                header.substring("Basic ".length()));
        String[] parts = new String(
                bytes, StandardCharsets.UTF_8).split(":", 2);
        String[] auth = suffix.split("/", 2);
        if (auth.length != 2 || !Arrays.equals(auth, parts)) {
            servletResponse.setStatus(failureStatus);
            return;
        }

        JSONObject response = new JSONObject();
        response.put("authenticated", true);
        response.put("user", parts[0]);
        respondJSON(servletResponse, os, response);
    }
}
