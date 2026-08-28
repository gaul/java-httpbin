/*
 * Copyright 2018-2023 Andrew Gaul <andrew@gaul.org>
 * Copyright 2015-2016 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpBinHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(
            HttpBinHandler.class);
    private static final int MAX_DELAY_MS = 10 * 1000;
    // Buffer parts in memory instead of spilling them to temporary files,
    // matching the previous MultiPartFormInputStream behavior.  Parts remain
    // bounded by the default maximum part and request sizes.
    private static final MultiPartConfig MULTI_PART_CONFIG =
            new MultiPartConfig.Builder()
                    .maxMemoryPartSize(-1)
                    .build();

    @Override
    public boolean handle(Request request, Response response,
            Callback callback) throws IOException {
        logger.trace("request: {}", request);
        HttpFields headers = request.getHeaders();
        for (String headerName : headers.getFieldNamesCollection()) {
            logger.trace("header: {}: {}", headerName,
                    headers.get(headerName));
        }
        try (InputStream is = Content.Source.asInputStream(request);
             OutputStream os = Response.asBufferedOutputStream(request,
                     response)) {
            handleHelper(request, response, is, os);
        }
        callback.succeeded();
        return true;
    }

    private void handleHelper(Request request, Response response,
            InputStream is, OutputStream os) throws IOException {
        String method = request.getMethod();
        String uri = request.getHttpURI().getPath();
        Fields params = Request.extractQueryParameters(request);
        try {
            if (uri.equals("/")) {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                copyResource(response, os, "/home.html");
                return;
            } else if (uri.startsWith("/status/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                int status;
                try {
                    status = Integer.parseInt(uri.substring(
                            "/status/".length()));
                } catch (NumberFormatException nfe) {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    return;
                }
                response.setStatus(status);
                if (status >= 300 && status < 400) {
                    response.getHeaders().put(HttpHeader.LOCATION,
                            "/redirect/1");
                }
                return;
            } else if (method.equals("GET") && uri.equals("/headers")) {
                JSONObject headers = new JSONObject();
                HttpFields fields = request.getHeaders();
                for (String headerName : fields.getFieldNamesCollection()) {
                    headers.put(headerName, fields.get(headerName));
                }

                JSONObject json = new JSONObject();
                json.put("headers", headers);
                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.equals("/ip")) {
                JSONObject json = new JSONObject();
                json.put("origin", Request.getRemoteAddr(request));
                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.equals("/user-agent")) {
                JSONObject json = new JSONObject();
                json.put("user-agent", request.getHeaders().get(
                        HttpHeader.USER_AGENT));
                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.equals("/gzip")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject json = new JSONObject();
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));
                json.put("gzipped", true);

                byte[] uncompressed = json.toString(/*indent=*/ 2).getBytes(
                        StandardCharsets.UTF_8);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(
                        uncompressed.length);
                try (GZIPOutputStream gzipos = new GZIPOutputStream(baos)) {
                    gzipos.write(uncompressed);
                }
                byte[] compressed = baos.toByteArray();

                response.getHeaders().put(HttpHeader.CONTENT_LENGTH,
                        compressed.length);
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());
                response.setStatus(HttpStatus.OK_200);
                os.write(compressed);
                os.flush();
                return;
            } else if (method.equals("GET") && uri.equals("/deflate")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject json = new JSONObject();
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));
                json.put("deflated", true);

                byte[] uncompressed = json.toString(/*indent=*/ 2).getBytes(
                        StandardCharsets.UTF_8);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(
                        uncompressed.length);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(
                        baos, new Deflater(Deflater.DEFAULT_COMPRESSION,
                                /*nowrap=*/ true))) {
                    dos.write(uncompressed);
                }
                byte[] compressed = baos.toByteArray();

                response.getHeaders().put(HttpHeader.CONTENT_LENGTH,
                        compressed.length);
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING,
                        "deflate");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());
                response.setStatus(HttpStatus.OK_200);
                os.write(compressed);
                os.flush();
                return;
            } else if (method.equals("GET") && uri.equals("/cache")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                HttpFields fields = request.getHeaders();
                if (fields.get(HttpHeader.IF_MODIFIED_SINCE) != null ||
                        fields.get(HttpHeader.IF_NONE_MATCH) != null) {
                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                    return;
                }

                JSONObject json = new JSONObject();
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));

                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.startsWith("/cache/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int seconds = Integer.parseInt(uri.substring(
                        "/cache/".length()));

                JSONObject json = new JSONObject();
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));

                response.getHeaders().put(HttpHeader.CACHE_CONTROL,
                        "public, max-age=" + seconds);
                respondJSON(response, os, json);
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

                JSONObject json = new JSONObject();
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));

                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.startsWith("/etag/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                String eTag = uri.substring("/etag/".length());

                String ifMatch = request.getHeaders().get(HttpHeader.IF_MATCH);
                if (ifMatch == null) {
                    // nothing
                } else if (ifMatch.equals("*") || ifMatch.equals(eTag)) {
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().put(HttpHeader.ETAG, eTag);
                    return;
                } else {
                    response.setStatus(HttpStatus.PRECONDITION_FAILED_412);
                    return;
                }

                String ifNoneMatch = request.getHeaders().get(
                        HttpHeader.IF_NONE_MATCH);
                if (ifNoneMatch == null) {
                    // nothing
                } else if (ifNoneMatch.equals("*") || ifNoneMatch.equals(
                        eTag)) {
                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                    response.getHeaders().put(HttpHeader.ETAG, eTag);
                    return;
                }

                response.getHeaders().put(HttpHeader.ETAG, eTag);
                return;
            } else if (method.equals("GET") && uri.equals("/drip")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                long durationMs = (long) (1000 * Utils.getDoubleParameter(
                        params, "duration", 0.0));
                int numBytes = Utils.getIntParameter(params, "numbytes", 10);
                if (numBytes <= 0) {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    return;
                }
                int code = Utils.getIntParameter(params, "code", 200);
                int delay = Utils.getIntParameter(params, "delay", 0);

                response.setStatus(code);
                Utils.sleepUninterruptibly(delay, TimeUnit.SECONDS);

                for (int i = 0; i < numBytes; ++i) {
                    Utils.sleepUninterruptibly(durationMs / numBytes,
                            TimeUnit.MILLISECONDS);
                    os.write('*');
                }

                return;
            } else if (method.equals("GET") && uri.startsWith("/stream/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int responses = Integer.parseInt(uri.substring(
                        "/stream/".length()));

                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());
                response.setStatus(HttpStatus.OK_200);

                for (int i = 0; i < responses; ++i) {
                    Utils.sleepUninterruptibly(1, TimeUnit.SECONDS);

                    JSONObject json = new JSONObject();
                    json.put("args", mapParametersToJSON(params));
                    json.put("headers", mapHeadersToJSON(request));
                    json.put("origin", Request.getRemoteAddr(request));
                    json.put("url", getFullURL(request));
                    json.put("id", i);

                    byte[] body = json.toString().getBytes(
                            StandardCharsets.UTF_8);
                    os.write(body);
                    os.write('\n');
                    os.flush();
                }

                return;
            } else if (method.equals("GET") && uri.startsWith(
                    "/stream-bytes/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                long numBytes = Long.parseLong(uri.substring(
                        "/stream-bytes/".length()));

                int seed = Utils.getIntParameter(params, "seed", -1);
                int chunkSize = Utils.getIntParameter(params, "chunkSize",
                        200);
                byte[] buf = new byte[chunkSize];
                Random random = seed == -1 ? new Random() : new Random(seed);

                response.setStatus(HttpStatus.OK_200);

                for (long i = 0; i < numBytes; i += chunkSize) {
                    random.nextBytes(buf);
                    os.write(buf, 0, i + chunkSize > numBytes ?
                            (int) (numBytes - i) : chunkSize);
                }

                return;
            } else if ((method.equals("DELETE") && uri.equals("/delete")) ||
                    (method.equals("GET") && uri.equals("/get")) ||
                    (method.equals("PATCH") && uri.equals("/patch")) ||
                    (method.equals("POST") && uri.equals("/post")) ||
                    (method.equals("PUT") && uri.equals("/put"))) {
                JSONObject json = new JSONObject();

                String contentType = request.getHeaders().get(
                        HttpHeader.CONTENT_TYPE);
                if (contentType != null && contentType.startsWith(
                        "multipart/form-data")) {
                    JSONObject data = new JSONObject();
                    try (MultiPartFormData.Parts parts =
                            MultiPartFormData.getParts(request, request,
                                    contentType, MULTI_PART_CONFIG)) {
                        for (MultiPart.Part part : parts) {
                            data.put(part.getName(), part.getContentAsString(
                                    StandardCharsets.UTF_8));
                        }
                    }
                    json.put("data", "");
                    json.put("form", data);
                    json.put("json", JSONObject.NULL);
                } else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    Utils.copy(is, baos);
                    String string = new String(
                            baos.toByteArray(), StandardCharsets.UTF_8);
                    json.put("data", string);
                    try {
                        json.put("json", new JSONObject(string));
                    } catch (JSONException e) {
                        // client can provide non-JSON data
                    }
                }

                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));

                respondJSON(response, os, json);
                return;
            } else if (uri.equals("/redirect-to")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                int statusCode = Utils.getIntParameter(params, "status_code",
                        HttpStatus.MOVED_TEMPORARILY_302);
                redirectTo(response, params.getValue("url"), statusCode);
                return;
            } else if (uri.startsWith("/redirect/") ||
                    uri.startsWith("/relative-redirect/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        uri.startsWith("/redirect/") ?
                                "/redirect/".length() :
                                "/relative-redirect/".length())) - 1;
                if (count > 0) {
                    StringBuilder path = new StringBuilder();
                    if ("true".equals(params.getValue("absolute"))) {
                        path.append(getRequestURL(request));
                        path.setLength(path.length() - uri.length());
                        path.append("/absolute-redirect/");
                    } else {
                        path.append("/relative-redirect/");
                    }
                    path.append(count);
                    redirectTo(response, path.toString());
                } else {
                    redirectTo(response, "/get");
                }

                return;
            } else if (uri.startsWith("/absolute-redirect/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        "/absolute-redirect/".length())) - 1;
                StringBuilder path = new StringBuilder(
                        getRequestURL(request));
                path.setLength(path.length() - uri.length());
                if (count > 0) {
                    path.append("/absolute-redirect/")
                            .append(count);
                    redirectTo(response, path.toString());
                } else {
                    path.append("/get");
                    redirectTo(response, path.toString());
                }

                return;
            } else if (method.equals("GET") &&
                    uri.equals("/response-headers")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                for (String paramName : params.getNames()) {
                    response.getHeaders().add(paramName, params.getValue(
                            paramName));
                }
                response.setStatus(HttpStatus.OK_200);
                return;
            } else if (uri.equals("/cookies")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject cookies = new JSONObject();

                for (HttpCookie cookie : Request.getCookies(request)) {
                    cookies.put(cookie.getName(), cookie.getValue());
                }

                JSONObject json = new JSONObject();
                json.put("cookies", cookies);

                respondJSON(response, os, json);
                return;
            } else if (uri.startsWith("/cookies/set")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (String name : params.getNames()) {
                    for (String value : params.getValues(name)) {
                        response.getHeaders().add(HttpHeader.SET_COOKIE,
                                "%s=%s; Path=/".formatted(name, value));
                    }
                }

                response.getHeaders().put(HttpHeader.LOCATION, "/cookies");
                response.setStatus(HttpStatus.MOVED_TEMPORARILY_302);
                return;
            } else if (uri.startsWith("/cookies/delete")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (String name : params.getNames()) {
                    response.getHeaders().add(HttpHeader.SET_COOKIE,
                            "%s=; Path=/".formatted(name));
                }

                response.getHeaders().put(HttpHeader.LOCATION, "/cookies");
                response.setStatus(HttpStatus.MOVED_TEMPORARILY_302);
                return;
            } else if (uri.startsWith("/basic-auth/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, response, os,
                        uri.substring("/basic-auth/".length()),
                        HttpStatus.UNAUTHORIZED_401);
                return;
            } else if (uri.startsWith("/hidden-basic-auth/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, response, os,
                        uri.substring("/hidden-basic-auth/".length()),
                        HttpStatus.NOT_FOUND_404);
                return;
            } else if (uri.startsWith("/anything")) {
                response.setStatus(HttpStatus.OK_200);

                final JSONObject json = new JSONObject();

                // Method
                json.put("method", method);
                json.put("args", mapParametersToJSON(params));
                json.put("headers", mapHeadersToJSON(request));
                json.put("origin", Request.getRemoteAddr(request));
                json.put("url", getFullURL(request));

                // Body data
                final ByteArrayOutputStream data = new ByteArrayOutputStream();
                Utils.copy(is, data);

                json.put("data", data.toString(StandardCharsets.UTF_8));
                respondJSON(response, os, json);
                return;
            } else if (method.equals("GET") && uri.startsWith("/bytes/")) {
                long length = Long.parseLong(uri.substring(
                        "/bytes/".length()));
                int seed = Utils.getIntParameter(params, "seed", -1);
                Random random = seed != -1 ?  new Random(seed) : new Random();

                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
                byte[] buffer = new byte[4096];
                for (long i = 0; i < length;) {
                    int count = (int) Math.min(buffer.length, length - i);
                    random.nextBytes(buffer);
                    os.write(buffer, 0, count);
                    i += count;
                }
                return;
            } else if (method.equals("GET") && uri.startsWith("/base64/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                byte[] body = Base64.getDecoder().decode(
                        uri.substring("/base64/".length()));
                response.setStatus(HttpStatus.OK_200);
                os.write(body);
                os.flush();
                return;
            } else if (method.equals("GET") && uri.startsWith("/range/")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                long size = Long.parseLong(uri.substring("/range/".length()));
                long start;
                long end;
                String range = request.getHeaders().get(HttpHeader.RANGE);
                if (range != null && range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    String[] ranges = range.split("-", 2);
                    if (ranges[0].isEmpty()) {
                        start = size - Long.parseLong(ranges[1]);
                        end = size - 1;
                    } else if (ranges[1].isEmpty()) {
                        start = Long.parseLong(ranges[0]);
                        end = size - 1;
                    } else {
                        start = Long.parseLong(ranges[0]);
                        end = Long.parseLong(ranges[1]);
                    }
                    if (end + 1 > size || start > end) {
                        response.setStatus(
                                HttpStatus.RANGE_NOT_SATISFIABLE_416);
                        response.getHeaders().add(HttpHeader.ETAG,
                                "range" + size);
                        response.getHeaders().add(HttpHeader.CONTENT_RANGE,
                                "bytes */" + size);
                        return;
                    }
                    response.setStatus(HttpStatus.PARTIAL_CONTENT_206);
                } else {
                    start = 0;
                    end = size - 1;
                    response.setStatus(HttpStatus.OK_200);
                }

                response.getHeaders().add(HttpHeader.ETAG, "range" + size);
                response.getHeaders().add(HttpHeader.CONTENT_LENGTH,
                        String.valueOf(end - start + 1));
                response.getHeaders().add(HttpHeader.CONTENT_RANGE,
                        "bytes " + start + "-" + end + "/" + size);
                response.getHeaders().add(HttpHeader.ACCEPT_RANGES, "bytes");

                for (long i = start; i <= end; ++i) {
                    os.write((char) ('a' + (i % 26)));
                }
                os.flush();

                return;
            } else if (method.equals("GET") && uri.equals("/image/jpeg")) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE,
                        "image/jpeg");
                copyResource(response, os, "/image.jpg");
                return;
            } else if (method.equals("GET") && uri.equals("/image/png")) {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE,
                        "image/png");
                copyResource(response, os, "/image.png");
                return;
            } else if (method.equals("GET") && uri.equals("/html")) {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                copyResource(response, os, "/text.html");
                return;
            } else if (method.equals("GET") && uri.equals("/xml")) {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().add(HttpHeader.CONTENT_TYPE,
                        "application/xml");
                copyResource(response, os, "/text.xml");
                return;
            } else if (method.equals("GET") && uri.equals("/robots.txt")) {
                byte[] output = "User-agent: *\nDisallow: /deny\n".getBytes(
                        StandardCharsets.UTF_8);

                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_PLAIN.asString());
                os.write(output);
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

                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_PLAIN.asString());
                os.write(output);
                return;
            }
            response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
        } catch (JSONException e) {
            logger.trace("JSONException", e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
        }
    }

    private static void respondJSON(Response response, OutputStream os,
            JSONObject obj) throws IOException {
        byte[] body = obj.toString(/*indent=*/ 2).getBytes(
                StandardCharsets.UTF_8);

        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                MimeTypes.Type.APPLICATION_JSON.asString());
        response.setStatus(HttpStatus.OK_200);
        os.write(body);
        os.flush();
    }

    private static void redirectTo(Response response, String location,
            int statusCode) {
        response.getHeaders().put(HttpHeader.LOCATION, location);
        response.setStatus(statusCode);
    }

    private static void redirectTo(Response response, String location) {
        redirectTo(response, location, HttpStatus.MOVED_TEMPORARILY_302);
    }

    private void copyResource(Response response, OutputStream os,
            String resource) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            long length = Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
        }
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            Utils.copy(is, os);
        }
    }

    private static JSONObject mapHeadersToJSON(Request request) {
        JSONObject headers = new JSONObject();

        HttpFields fields = request.getHeaders();
        for (String name : fields.getFieldNamesCollection()) {
            List<String> values = fields.getValuesList(name);
            if (values.size() == 1) {
                headers.put(name, values.get(0));
            } else {
                headers.put(name, new JSONArray(values));
            }
        }

        return headers;
    }

    private static JSONObject mapParametersToJSON(Fields params) {
        JSONObject headers = new JSONObject();

        for (String name : params.getNames()) {
            List<String> values = params.getValues(name);
            if (values.size() == 1) {
                headers.put(name, values.get(0));
            } else {
                headers.put(name, new JSONArray(values));
            }
        }

        return headers;
    }

    private static String getFullURL(Request request) {
        return request.getHttpURI().asString();
    }

    private static String getRequestURL(Request request) {
        return HttpURI.build(request.getHttpURI()).query(null).asString();
    }

    private static void handleBasicAuth(Request request, Response response,
            OutputStream os, String suffix, int failureStatus)
            throws IOException {
        String header = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (header == null || !header.startsWith("Basic ")) {
            response.setStatus(failureStatus);
            return;
        }

        byte[] bytes = Base64.getDecoder().decode(
                header.substring("Basic ".length()));
        String[] parts = new String(
                bytes, StandardCharsets.UTF_8).split(":", 2);
        String[] auth = suffix.split("/", 2);
        if (auth.length != 2 || !Arrays.equals(auth, parts)) {
            response.setStatus(failureStatus);
            return;
        }

        JSONObject json = new JSONObject();
        json.put("authenticated", true);
        json.put("user", parts[0]);
        respondJSON(response, os, json);
    }
}
