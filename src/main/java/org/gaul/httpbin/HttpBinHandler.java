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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import org.eclipse.jetty.util.UrlEncoded;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpBinHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(
            HttpBinHandler.class);
    private static final int MAX_DELAY_MS = 10 * 1000;
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final List<String> ACCEPTED_MEDIA_TYPES = List.of(
            "image/webp", "image/svg+xml", "image/jpeg", "image/png",
            "image/*");
    private static final int MAX_LINKS = 200;
    private static final int MAX_STREAM = 100;
    // What a proxy or the platform underneath adds, which says more about
    // where a server runs than about the request that reached it.  Upstream
    // reports these only when the query names show_env.
    private static final Set<String> ENV_HEADERS = Set.of(
            "connect-time", "total-route-time", "via", "x-forwarded-for",
            "x-forwarded-port", "x-forwarded-proto", "x-forwarded-protocol",
            "x-forwarded-ssl", "x-heroku-dynos-in-use",
            "x-heroku-queue-depth", "x-heroku-queue-wait-time", "x-real-ip",
            "x-request-id", "x-request-start", "x-varnish");
    private static final Set<String> ENV_COOKIES = Set.of(
            "__utma", "__utmb", "__utmz", "_gauges_unique",
            "_gauges_unique_day", "_gauges_unique_hour",
            "_gauges_unique_month", "_gauges_unique_year");
    private static final String BASIC_REALM = "Basic realm=\"Fake Realm\"";
    // Upstream's own words, and part of what a client sees from it.
    private static final String PAYMENT_REQUIRED = "Fuck you, pay me!";
    private static final String TEAPOT = """

            -=[ teapot ]=-

               _...._
             .'  _ _ `.
            | ."` ^ `". _,
            \\_;`"---"`|//
              |       ;/
              \\_     _/
                `\"""`
        """;
    private static final String ANGRY = """

                  .-''''''-.
                .' _      _ '.
               /   O      O   \\
              :                :
              |                |
              :       __       :
               \\  .-"`  `"-.  /
                '.          .'
                  '-......-'
             YOU SHOULDN'T BE HERE
        """;
    // The keys that only a request body can answer.
    private static final List<String> BODY_KEYS =
            List.of("form", "files", "data", "json");
    // What a preflight is told it may use, which upstream fixes without
    // HEAD whatever the route Allow names.
    private static final String ACCESS_CONTROL_METHODS =
            "GET, POST, PUT, DELETE, PATCH, OPTIONS";
    // Buffer parts in memory instead of spilling them to temporary files,
    // matching the previous MultiPartFormInputStream behavior.  Parts remain
    // bounded by the default maximum part and request sizes.
    private static final MultiPartConfig MULTI_PART_CONFIG =
            new MultiPartConfig.Builder()
                    .maxMemoryPartSize(-1)
                    .build();

    private final String prefix;
    private final String cookiePath;

    public HttpBinHandler() {
        this("");
    }

    /**
     * Serves the httpbin endpoints beneath a path instead of at the server
     * root, so that /some/other/path/headers behaves like /headers does.
     *
     * @param prefix path to serve beneath, empty to serve at the root
     */
    public HttpBinHandler(String prefix) {
        this.prefix = normalizePrefix(prefix);
        // A cookie scoped to / would leak to anything else sharing the
        // origin, but RFC 6265 path matching makes Path=/some/other/path
        // cover /some/other/path/cookies as well as the prefix itself.
        this.cookiePath = this.prefix.isEmpty() ? "/" : this.prefix;
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("/")) {
            return "";
        }
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException(
                    "prefix must start with /: " + prefix);
        }
        String normalized = prefix.endsWith("/") ?
                prefix.substring(0, prefix.length() - 1) : prefix;
        // Requests are matched against the raw path, so a prefix that Jetty
        // would only produce after decoding could never match.  Rejecting it
        // beats serving nothing but 404.
        for (String segment : normalized.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") ||
                    segment.equals("..")) {
                throw new IllegalArgumentException(
                        "prefix must not contain empty or dot segments: " +
                                prefix);
            }
        }
        for (String reserved : new String[] {"%", ";", "?", "#"}) {
            if (normalized.contains(reserved)) {
                throw new IllegalArgumentException(
                        "prefix must not contain " + reserved + ": " + prefix);
            }
        }
        return normalized;
    }

    public String getPrefix() {
        return prefix;
    }

    /** Whether a route names a path exactly or the start of one. */
    private enum Match {
        EXACT, PREFIX
    }

    /**
     * The paths this server serves, and the methods each of them answers.
     *
     * <p>Declaring them is what lets a path none of them names answer 404,
     * and a method the one that matched does not answer 405 naming the ones
     * it does.  Order is match order, so a route another would also match
     * comes first, as /cookies/set does before /cookies/set/.
     */
    private enum Route {
        HOME("/", Match.EXACT, "GET"),
        STATUS("/status/", Match.PREFIX,
                "GET", "POST", "PUT", "DELETE", "PATCH",
                "TRACE"),
        HEADERS("/headers", Match.EXACT, "GET"),
        IP("/ip", Match.EXACT, "GET"),
        UUID("/uuid", Match.EXACT, "GET"),
        USER_AGENT("/user-agent", Match.EXACT, "GET"),
        GZIP("/gzip", Match.EXACT, "GET"),
        DEFLATE("/deflate", Match.EXACT, "GET"),
        BROTLI("/brotli", Match.EXACT, "GET"),
        CACHE("/cache", Match.EXACT, "GET"),
        CACHE_SECONDS("/cache/", Match.PREFIX, "GET"),
        DELAY("/delay/", Match.PREFIX,
                "GET", "POST", "PUT", "DELETE", "PATCH",
                "TRACE"),
        ETAG("/etag/", Match.PREFIX, "GET"),
        DRIP("/drip", Match.EXACT, "GET"),
        STREAM("/stream/", Match.PREFIX, "GET"),
        STREAM_BYTES("/stream-bytes/", Match.PREFIX, "GET"),
        GET("/get", Match.EXACT, "GET"),
        DELETE("/delete", Match.EXACT, "DELETE"),
        PATCH("/patch", Match.EXACT, "PATCH"),
        POST("/post", Match.EXACT, "POST"),
        PUT("/put", Match.EXACT, "PUT"),
        LINKS("/links/", Match.PREFIX, "GET"),
        REDIRECT_TO("/redirect-to", Match.EXACT,
                "GET", "POST", "PUT", "DELETE", "PATCH",
                "TRACE"),
        REDIRECT("/redirect/", Match.PREFIX, "GET"),
        RELATIVE_REDIRECT("/relative-redirect/", Match.PREFIX, "GET"),
        ABSOLUTE_REDIRECT("/absolute-redirect/", Match.PREFIX, "GET"),
        RESPONSE_HEADERS("/response-headers", Match.EXACT, "GET", "POST"),
        COOKIES("/cookies", Match.EXACT, "GET"),
        COOKIES_SET("/cookies/set", Match.EXACT, "GET"),
        COOKIES_SET_PATH("/cookies/set/", Match.PREFIX, "GET"),
        COOKIES_DELETE("/cookies/delete", Match.EXACT, "GET"),
        BASIC_AUTH("/basic-auth/", Match.PREFIX, "GET"),
        HIDDEN_BASIC_AUTH("/hidden-basic-auth/", Match.PREFIX, "GET"),
        DIGEST_AUTH("/digest-auth/", Match.PREFIX, "GET"),
        BEARER("/bearer", Match.EXACT, "GET"),
        ANYTHING("/anything", Match.EXACT,
                "GET", "POST", "PUT", "DELETE", "PATCH",
                "TRACE"),
        ANYTHING_PATH("/anything/", Match.PREFIX,
                "GET", "POST", "PUT", "DELETE", "PATCH",
                "TRACE"),
        BYTES("/bytes/", Match.PREFIX, "GET"),
        BASE64("/base64/", Match.PREFIX, "GET"),
        RANGE("/range/", Match.PREFIX, "GET"),
        IMAGE_JPEG("/image/jpeg", Match.EXACT, "GET"),
        IMAGE_PNG("/image/png", Match.EXACT, "GET"),
        IMAGE_SVG("/image/svg", Match.EXACT, "GET"),
        IMAGE_WEBP("/image/webp", Match.EXACT, "GET"),
        IMAGE("/image", Match.EXACT, "GET"),
        HTML("/html", Match.EXACT, "GET"),
        XML("/xml", Match.EXACT, "GET"),
        JSON("/json", Match.EXACT, "GET"),
        ENCODING_UTF8("/encoding/utf8", Match.EXACT, "GET"),
        FORMS_POST("/forms/post", Match.EXACT, "GET"),
        ROBOTS_TXT("/robots.txt", Match.EXACT, "GET"),
        DENY("/deny", Match.EXACT, "GET");

        private final String path;
        private final Match match;
        private final Set<String> methods;
        private final String allow;

        Route(String path, Match match, String... methods) {
            this.path = path;
            this.match = match;
            this.methods = Set.of(methods);
            // Whatever a route answers, it answers OPTIONS, and a HEAD
            // wherever it answers a GET.
            List<String> allowed = new ArrayList<>();
            for (String candidate : new String[] {
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "TRACE",
            }) {
                if (answers(candidate)) {
                    allowed.add(candidate);
                }
            }
            allowed.add("OPTIONS");
            this.allow = String.join(", ", allowed);
        }

        static Route match(String uri) {
            for (Route route : values()) {
                if (route.match == Match.EXACT ? uri.equals(route.path) :
                        uri.startsWith(route.path)) {
                    return route;
                }
            }
            return null;
        }

        boolean answers(String method) {
            return methods.contains(method) || (method.equals("HEAD") &&
                    methods.contains("GET"));
        }

        String allow() {
            return allow;
        }
    }

    @Override
    public boolean handle(Request request, Response response,
            Callback callback) throws IOException {
        logger.trace("request: {}", request);
        HttpFields headers = request.getHeaders();
        for (String headerName : headers.getFieldNamesCollection()) {
            logger.trace("header: {}: {}", headerName,
                    headers.get(headerName));
        }
        setCorsHeaders(request, response);
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
        String uri = stripPrefix(request.getHttpURI().getPath());
        if (uri == null) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            return;
        }
        Fields params = Request.extractQueryParameters(request);
        // A HEAD asks for what a GET would answer without the body, which
        // Jetty leaves off while keeping the Content-Length it would have
        // had, so every route a GET reaches answers one.
        Route route = Route.match(uri);
        if (route == null) {
            Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
            response.setStatus(HttpStatus.NOT_FOUND_404);
            return;
        }
        if (method.equals("OPTIONS") || !route.answers(method)) {
            // A browser discards a preflight that does not succeed, so an
            // OPTIONS is answered rather than refused; anything else this
            // route does not answer is refused, naming what it does.
            Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
            response.getHeaders().put(HttpHeader.ALLOW, route.allow());
            response.setStatus(method.equals("OPTIONS") ?
                    HttpStatus.OK_200 : HttpStatus.METHOD_NOT_ALLOWED_405);
            return;
        }

        try {
            if (route == Route.HOME) {
                serveResource(response, os,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString(),
                        "/home.html");
                return;
            } else if (route == Route.STATUS) {
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
                switch (status) {
                case HttpStatus.MOVED_PERMANENTLY_301:
                case HttpStatus.MOVED_TEMPORARILY_302:
                case HttpStatus.SEE_OTHER_303:
                case HttpStatus.USE_PROXY_305:
                case HttpStatus.TEMPORARY_REDIRECT_307:
                    response.getHeaders().put(HttpHeader.LOCATION,
                            prefix + "/redirect/1");
                    break;
                case HttpStatus.UNAUTHORIZED_401:
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE,
                            BASIC_REALM);
                    break;
                case HttpStatus.PAYMENT_REQUIRED_402:
                    response.getHeaders().put("x-more-info",
                            "http://vimeo.com/22053820");
                    respondBytes(response, os, PAYMENT_REQUIRED.getBytes(
                            StandardCharsets.UTF_8));
                    break;
                case HttpStatus.NOT_ACCEPTABLE_406:
                    respondNotAcceptable(response, os);
                    break;
                case HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407:
                    response.getHeaders().put(HttpHeader.PROXY_AUTHENTICATE,
                            BASIC_REALM);
                    break;
                case HttpStatus.IM_A_TEAPOT_418:
                    response.getHeaders().put("x-more-info",
                            "http://tools.ietf.org/html/rfc2324");
                    respondBytes(response, os, TEAPOT.getBytes(
                            StandardCharsets.UTF_8));
                    break;
                default:
                    break;
                }
                return;
            } else if (route == Route.HEADERS) {
                JSONObject headers = new JSONObject();
                HttpFields fields = request.getHeaders();
                boolean showEnv = showEnv(params);
                for (String headerName : fields.getFieldNamesCollection()) {
                    if (showEnv || !ENV_HEADERS.contains(
                            headerName.toLowerCase(Locale.ROOT))) {
                        headers.put(headerName, fields.get(headerName));
                    }
                }

                JSONObject json = new JSONObject();
                json.put("headers", headers);
                respondJSON(response, os, json);
                return;
            } else if (route == Route.IP) {
                JSONObject json = new JSONObject();
                json.put("origin", getOrigin(request));
                respondJSON(response, os, json);
                return;
            } else if (route == Route.UUID) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                JSONObject json = new JSONObject();
                json.put("uuid", UUID.randomUUID().toString());
                respondJSON(response, os, json);
                return;
            } else if (route == Route.USER_AGENT) {
                JSONObject json = new JSONObject();
                json.put("user-agent", request.getHeaders().get(
                        HttpHeader.USER_AGENT));
                respondJSON(response, os, json);
                return;
            } else if (route == Route.GZIP) {
                JSONObject json = describeRequest(request, is, params,
                        "origin", "headers", "method");
                json.put("gzipped", true);

                byte[] uncompressed = jsonBody(json);
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
            } else if (route == Route.DEFLATE) {
                JSONObject json = describeRequest(request, is, params,
                        "origin", "headers", "method");
                json.put("deflated", true);

                byte[] uncompressed = jsonBody(json);
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
            } else if (route == Route.BROTLI) {
                JSONObject json = describeRequest(request, is, params,
                        "origin", "headers", "method");
                json.put("brotli", true);

                byte[] compressed = Brotli.encode(jsonBody(json));

                response.getHeaders().put(HttpHeader.CONTENT_LENGTH,
                        compressed.length);
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "br");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());
                response.setStatus(HttpStatus.OK_200);
                os.write(compressed);
                os.flush();
                return;
            } else if (route == Route.CACHE) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                HttpFields fields = request.getHeaders();
                if (fields.get(HttpHeader.IF_MODIFIED_SINCE) != null ||
                        fields.get(HttpHeader.IF_NONE_MATCH) != null) {
                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                    return;
                }

                response.getHeaders().putDate(HttpHeader.LAST_MODIFIED,
                        System.currentTimeMillis());
                response.getHeaders().put(HttpHeader.ETAG,
                        UUID.randomUUID().toString().replace("-", ""));
                respondJSON(response, os, describeRequest(request, is,
                        params, "url", "args", "headers", "origin"));
                return;
            } else if (route == Route.CACHE_SECONDS) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int seconds = Integer.parseInt(uri.substring(
                        "/cache/".length()));

                JSONObject json = describeRequest(request, is, params,
                        "url", "args", "headers", "origin");

                response.getHeaders().put(HttpHeader.CACHE_CONTROL,
                        "public, max-age=" + seconds);
                respondJSON(response, os, json);
                return;
            } else if (route == Route.DELAY) {
                int delayMs = (int) (1000 * Double.parseDouble(uri.substring(
                        "/delay/".length())));
                try {
                    Thread.sleep(Math.min(delayMs, MAX_DELAY_MS));
                } catch (InterruptedException ie) {
                    // ignore
                }

                respondJSON(response, os, describeRequest(request, is, params,
                        "url", "args", "form", "data", "origin", "headers",
                        "files"));
                return;
            } else if (route == Route.ETAG) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                String eTag = uri.substring("/etag/".length());
                HttpFields fields = request.getHeaders();
                List<String> ifNoneMatch = parseMultiValueHeader(
                        fields.get(HttpHeader.IF_NONE_MATCH));
                List<String> ifMatch = parseMultiValueHeader(
                        fields.get(HttpHeader.IF_MATCH));

                // Upstream lets If-None-Match answer on its own when both
                // arrive, so If-Match only applies without it.
                if (!ifNoneMatch.isEmpty()) {
                    if (ifNoneMatch.contains(eTag) ||
                            ifNoneMatch.contains("*")) {
                        response.setStatus(HttpStatus.NOT_MODIFIED_304);
                        response.getHeaders().put(HttpHeader.ETAG, eTag);
                        return;
                    }
                } else if (!ifMatch.isEmpty() && !ifMatch.contains(eTag) &&
                        !ifMatch.contains("*")) {
                    response.setStatus(HttpStatus.PRECONDITION_FAILED_412);
                    return;
                }

                response.getHeaders().put(HttpHeader.ETAG, eTag);
                respondJSON(response, os, describeRequest(request, is, params,
                        "url", "args", "headers", "origin"));
                return;
            } else if (route == Route.DRIP) {
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

                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        OCTET_STREAM);
                response.setStatus(code);
                Utils.sleepUninterruptibly(delay, TimeUnit.SECONDS);

                for (int i = 0; i < numBytes; ++i) {
                    Utils.sleepUninterruptibly(durationMs / numBytes,
                            TimeUnit.MILLISECONDS);
                    os.write('*');
                }

                return;
            } else if (route == Route.STREAM) {
                // Upstream streams as fast as it can write and stops at a
                // hundred lines, so asking for more is not a way to make a
                // server spend a long time answering.
                int responses = Math.min(Integer.parseInt(uri.substring(
                        "/stream/".length())), MAX_STREAM);

                // The lines differ only in their id, so the rest is read
                // once, before the first of them goes out.
                JSONObject json = describeRequest(request, is, params,
                        "url", "args", "headers", "origin");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());
                response.setStatus(HttpStatus.OK_200);

                for (int i = 0; i < responses; ++i) {
                    json.put("id", i);
                    os.write(json.toString().getBytes(
                            StandardCharsets.UTF_8));
                    os.write('\n');
                    // A line a client cannot read yet is not streamed.
                    os.flush();
                }

                return;
            } else if (route == Route.STREAM_BYTES) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                long numBytes = Long.parseLong(uri.substring(
                        "/stream-bytes/".length()));

                int seed = Utils.getIntParameter(params, "seed", -1);
                int chunkSize = Utils.getIntParameter(params, "chunkSize",
                        200);
                byte[] buf = new byte[chunkSize];
                Random random = seed == -1 ?
                        ThreadLocalRandom.current() : new Random(seed);

                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        OCTET_STREAM);
                response.setStatus(HttpStatus.OK_200);

                for (long i = 0; i < numBytes; i += chunkSize) {
                    random.nextBytes(buf);
                    os.write(buf, 0, i + chunkSize > numBytes ?
                            (int) (numBytes - i) : chunkSize);
                }

                return;
            } else if (route == Route.GET) {
                // Upstream reports no body here, the route taking none.
                respondJSON(response, os, describeRequest(request, is, params,
                        "url", "args", "headers", "origin"));
                return;
            } else if (route == Route.DELETE || route == Route.PATCH ||
                    route == Route.POST || route == Route.PUT) {
                respondJSON(response, os, describeRequest(request, is, params,
                        "url", "args", "form", "data", "origin", "headers",
                        "files", "json"));
                return;
            } else if (route == Route.LINKS) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                String[] parts = uri.substring("/links/".length())
                        .split("/", -1);
                int count;
                int offset;
                try {
                    count = Integer.parseInt(parts[0]);
                    offset = parts.length == 1 ? -1 :
                            Integer.parseInt(parts[1]);
                } catch (NumberFormatException nfe) {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    return;
                }
                if (parts.length > 2) {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    return;
                }
                if (parts.length == 1) {
                    // Naming no link to leave out lands on the first.
                    redirectTo(response, prefix + "/links/" + count + "/0");
                    return;
                }

                count = Math.min(Math.max(1, count), MAX_LINKS);
                StringBuilder html = new StringBuilder(
                        "<html><head><title>Links</title></head><body>");
                for (int i = 0; i < count; ++i) {
                    if (i == offset) {
                        html.append(i).append(" ");
                    } else {
                        html.append("<a href=\'").append(prefix)
                                .append("/links/").append(count).append("/")
                                .append(i).append("\'>").append(i)
                                .append("</a> ");
                    }
                }
                html.append("</body></html>");

                byte[] body = html.toString().getBytes(
                        StandardCharsets.UTF_8);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH,
                        body.length);
                response.setStatus(HttpStatus.OK_200);
                os.write(body);
                os.flush();
                return;
            } else if (route == Route.REDIRECT_TO) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                int statusCode = Utils.getIntParameter(params, "status_code",
                        HttpStatus.MOVED_TEMPORARILY_302);
                redirectTo(response, params.getValue("url"), statusCode);
                return;
            } else if (route == Route.REDIRECT ||
                    route == Route.RELATIVE_REDIRECT) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        uri.startsWith("/redirect/") ?
                                "/redirect/".length() :
                                "/relative-redirect/".length())) - 1;
                if (count > 0) {
                    StringBuilder path = new StringBuilder();
                    if ("true".equals(params.getValue("absolute"))) {
                        // Already beneath the prefix, so do not add it again.
                        path.append(originAndPrefix(request));
                        path.append("/absolute-redirect/");
                    } else {
                        path.append(prefix).append("/relative-redirect/");
                    }
                    path.append(count);
                    redirectTo(response, path.toString());
                } else {
                    redirectTo(response, prefix + "/get");
                }

                return;
            } else if (route == Route.ABSOLUTE_REDIRECT) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                int count = Integer.parseInt(uri.substring(
                        "/absolute-redirect/".length())) - 1;
                // Already beneath the prefix, so do not add it again.
                StringBuilder path = new StringBuilder(
                        originAndPrefix(request));
                if (count > 0) {
                    path.append("/absolute-redirect/")
                            .append(count);
                    redirectTo(response, path.toString());
                } else {
                    path.append("/get");
                    redirectTo(response, path.toString());
                }

                return;
            } else if (route == Route.RESPONSE_HEADERS) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                // Collect the headers separately from the response, which
                // already carries the Date and Server that the transport
                // adds and upstream's body does not name.
                HttpFields.Mutable fields = HttpFields.build();
                for (String paramName : params.getNames()) {
                    for (String value : params.getValues(paramName)) {
                        fields.add(paramName, value);
                    }
                }
                fields.put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.APPLICATION_JSON.asString());

                // The body names Content-Length among the rest, so its own
                // length feeds back into it.  Render until that settles, as
                // upstream does; it terminates because a longer body only
                // ever adds digits.
                byte[] body = new byte[0];
                for (;;) {
                    fields.put(HttpHeader.CONTENT_LENGTH, body.length);
                    byte[] rendered = jsonBody(mapFieldsToJSON(fields));
                    boolean settled = rendered.length == body.length;
                    body = rendered;
                    if (settled) {
                        break;
                    }
                }

                response.getHeaders().add(fields);
                response.setStatus(HttpStatus.OK_200);
                os.write(body);
                os.flush();
                return;
            } else if (route == Route.COOKIES) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                JSONObject cookies = new JSONObject();

                boolean showEnv = showEnv(params);
                for (HttpCookie cookie : Request.getCookies(request)) {
                    if (showEnv || !ENV_COOKIES.contains(
                            cookie.getName().toLowerCase(Locale.ROOT))) {
                        cookies.put(cookie.getName(), cookie.getValue());
                    }
                }

                JSONObject json = new JSONObject();
                json.put("cookies", cookies);

                respondJSON(response, os, json);
                return;
            } else if (route == Route.COOKIES_SET) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (String name : params.getNames()) {
                    for (String value : params.getValues(name)) {
                        setCookie(response, name, value);
                    }
                }

                redirectTo(response, prefix + "/cookies");
                return;
            } else if (route == Route.COOKIES_SET_PATH) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                // /cookies/set/name/value names one cookie in the path
                // rather than the query.
                String[] cookie = uri.substring(
                        "/cookies/set/".length()).split("/", -1);
                // Upstream's route matches neither an empty name nor an
                // empty value, so neither names a cookie to set here.
                if (cookie.length != 2 || cookie[0].isEmpty() ||
                        cookie[1].isEmpty()) {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    return;
                }
                setCookie(response, cookie[0], cookie[1]);

                redirectTo(response, prefix + "/cookies");
                return;
            } else if (route == Route.COOKIES_DELETE) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                for (String name : params.getNames()) {
                    // Emptying the value leaves the cookie in place; a
                    // client drops it only once it has expired.
                    response.getHeaders().add(HttpHeader.SET_COOKIE,
                            ("%s=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; " +
                                    "Max-Age=0; Path=%s").formatted(
                                    name, cookiePath));
                }

                redirectTo(response, prefix + "/cookies");
                return;
            } else if (route == Route.BASIC_AUTH) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, response, os,
                        uri.substring("/basic-auth/".length()),
                        HttpStatus.UNAUTHORIZED_401);
                return;
            } else if (route == Route.HIDDEN_BASIC_AUTH) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                handleBasicAuth(request, response, os,
                        uri.substring("/hidden-basic-auth/".length()),
                        HttpStatus.NOT_FOUND_404);
                return;
            } else if (route == Route.DIGEST_AUTH) {
                DigestAuth.handle(request, response, is, os,
                        uri.substring("/digest-auth/".length()), params,
                        cookiePath);
                return;
            } else if (route == Route.BEARER) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);

                String header = request.getHeaders().get(
                        HttpHeader.AUTHORIZATION);
                // A bare "Bearer" carries no token, so it fails as well.
                // Upstream reads "Bearer " as an empty token instead, which
                // no conforming server can tell from the bare one: a field
                // value arrives with its trailing space already stripped.
                if (header == null || !header.startsWith("Bearer ")) {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE,
                            "Bearer");
                    response.setStatus(HttpStatus.UNAUTHORIZED_401);
                    return;
                }

                JSONObject json = new JSONObject();
                json.put("authenticated", true);
                json.put("token", header.substring("Bearer ".length()));
                respondJSON(response, os, json);
                return;
            } else if (route == Route.ANYTHING ||
                    route == Route.ANYTHING_PATH) {
                respondJSON(response, os, describeRequest(request, is, params,
                        "url", "args", "headers", "origin", "method", "form",
                        "data", "files", "json"));
                return;
            } else if (route == Route.BYTES) {
                long length = Long.parseLong(uri.substring(
                        "/bytes/".length()));
                int seed = Utils.getIntParameter(params, "seed", -1);
                Random random = seed != -1 ?
                        new Random(seed) : ThreadLocalRandom.current();

                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        OCTET_STREAM);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
                byte[] buffer = new byte[4096];
                for (long i = 0; i < length;) {
                    int count = (int) Math.min(buffer.length, length - i);
                    random.nextBytes(buffer);
                    os.write(buffer, 0, count);
                    i += count;
                }
                return;
            } else if (route == Route.BASE64) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                byte[] body = Base64.getDecoder().decode(
                        uri.substring("/base64/".length()));
                // Upstream returns the decoded bytes as a plain string,
                // which Flask types as HTML whatever they hold.
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                response.setStatus(HttpStatus.OK_200);
                os.write(body);
                os.flush();
                return;
            } else if (route == Route.RANGE) {
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
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        OCTET_STREAM);
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
            } else if (route == Route.IMAGE_JPEG) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                serveResource(response, os, "image/jpeg",
                        "/image.jpg");
                return;
            } else if (route == Route.IMAGE_PNG) {
                serveResource(response, os, "image/png",
                        "/image.png");
                return;
            } else if (route == Route.IMAGE_SVG) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                serveResource(response, os, "image/svg+xml", "/image.svg");
                return;
            } else if (route == Route.IMAGE_WEBP) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                serveResource(response, os, "image/webp", "/image.webp");
                return;
            } else if (route == Route.IMAGE) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                String accept = request.getHeaders().get(HttpHeader.ACCEPT);
                // Upstream reads the header rather than negotiating over it,
                // so the first type it names that this serves wins.
                accept = accept == null ? "image/png" :
                        accept.toLowerCase(Locale.ROOT);
                if (accept.contains("image/webp")) {
                    serveResource(response, os, "image/webp", "/image.webp");
                } else if (accept.contains("image/svg+xml")) {
                    serveResource(response, os, "image/svg+xml",
                            "/image.svg");
                } else if (accept.contains("image/jpeg")) {
                    serveResource(response, os, "image/jpeg", "/image.jpg");
                } else if (accept.contains("image/png") ||
                        accept.contains("image/*")) {
                    serveResource(response, os, "image/png", "/image.png");
                } else {
                    respondNotAcceptable(response, os);
                }
                return;
            } else if (route == Route.HTML) {
                serveResource(response, os,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString(),
                        "/text.html");
                return;
            } else if (route == Route.XML) {
                serveResource(response, os, "application/xml",
                        "/text.xml");
                return;
            } else if (route == Route.JSON) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                serveResource(response, os,
                        MimeTypes.Type.APPLICATION_JSON.asString(),
                        "/slideshow.json");
                return;
            } else if (route == Route.ENCODING_UTF8) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                serveResource(response, os,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString(),
                        "/utf8.html");
                return;
            } else if (route == Route.FORMS_POST) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                // The form posts back to this server, so where it posts to
                // has to follow the prefix this one is serving beneath.
                byte[] body = readResource("/forms-post.html").replace(
                        "action=\"/post\"",
                        "action=\"" + prefix + "/post\"").getBytes(
                                StandardCharsets.UTF_8);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH,
                        body.length);
                response.setStatus(HttpStatus.OK_200);
                os.write(body);
                os.flush();
                return;
            } else if (route == Route.ROBOTS_TXT) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_PLAIN.asString());
                respondBytes(response, os, ("User-agent: *\nDisallow: " +
                        prefix + "/deny\n").getBytes(
                                StandardCharsets.UTF_8));
                return;
            } else if (route == Route.DENY) {
                Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                        MimeTypes.Type.TEXT_PLAIN.asString());
                respondBytes(response, os, ANGRY.getBytes(
                        StandardCharsets.UTF_8));
                return;
            }
            // Only a route the table names but nothing above serves.
            response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
        } catch (JSONException e) {
            logger.trace("JSONException", e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
        } catch (NumberFormatException nfe) {
            // A route takes a number in its path, and this path has
            // something else there, so it names no route after all.  The
            // parse comes before anything is written, so the status stands.
            logger.trace("NumberFormatException", nfe);
            response.setStatus(HttpStatus.NOT_FOUND_404);
        }
    }

    /**
     * Answers a request with the CORS headers httpbin sends.
     *
     * <p>Upstream sets these from an after_request hook, so they reach error
     * responses too, and the preflight ones follow the request method rather
     * than whether any route matched.
     *
     * @param request request being answered
     * @param response response to carry the headers
     */
    private static void setCorsHeaders(Request request, Response response) {
        HttpFields fields = request.getHeaders();
        HttpFields.Mutable headers = response.getHeaders();
        String origin = fields.get(HttpHeader.ORIGIN);
        headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN,
                origin != null ? origin : "*");
        headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

        if (!request.getMethod().equals("OPTIONS")) {
            return;
        }
        headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS,
                ACCESS_CONTROL_METHODS);
        headers.put(HttpHeader.ACCESS_CONTROL_MAX_AGE, "3600");
        String requested = fields.get(
                HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS);
        if (requested != null) {
            headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, requested);
        }
    }

    /**
     * Renders a JSON body the way httpbin does, trailing newline included.
     *
     * <p>Upstream appends one in its jsonify wrapper, so a client comparing
     * bodies byte for byte sees the same thing from either implementation.
     *
     * @param obj object to render
     * @return the encoded body
     */
    private static byte[] jsonBody(JSONObject obj) {
        return (obj.toString(/*indent=*/ 2) + "\n").getBytes(
                StandardCharsets.UTF_8);
    }

    private static void respondJSON(Response response, OutputStream os,
            JSONObject obj) throws IOException {
        respondJSON(response, os, obj, HttpStatus.OK_200);
    }

    static void respondJSON(Response response, OutputStream os,
            JSONObject obj, int status) throws IOException {
        byte[] body = jsonBody(obj);

        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE,
                MimeTypes.Type.APPLICATION_JSON.asString());
        response.setStatus(status);
        os.write(body);
        os.flush();
    }

    private void setCookie(Response response, String name, String value) {
        response.getHeaders().add(HttpHeader.SET_COOKIE,
                "%s=%s; Path=%s".formatted(name, value, cookiePath));
    }

    /** Writes a body the caller has already chosen a status for. */
    private static void respondBytes(Response response, OutputStream os,
            byte[] body) throws IOException {
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
        os.write(body);
        os.flush();
    }

    private static void respondNotAcceptable(Response response,
            OutputStream os) throws IOException {
        JSONObject json = new JSONObject();
        json.put("message", "Client did not request a " +
                "supported media type.");
        json.put("accept", new JSONArray(ACCEPTED_MEDIA_TYPES));
        respondJSON(response, os, json, HttpStatus.NOT_ACCEPTABLE_406);
    }

    private static void redirectTo(Response response, String location,
            int statusCode) {
        response.getHeaders().put(HttpHeader.LOCATION, location);
        response.setStatus(statusCode);
    }

    private static void redirectTo(Response response, String location) {
        redirectTo(response, location, HttpStatus.MOVED_TEMPORARILY_302);
    }

    private void serveResource(Response response, OutputStream os,
            String contentType, String resource) throws IOException {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.setStatus(HttpStatus.OK_200);
        copyResource(response, os, resource);
    }

    private String readResource(String resource) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    /** What an endpoint reports about a request body. */
    private record Body(JSONObject form, JSONObject files, String data,
            Object json) {
    }

    /**
     * Reads a request body the way the endpoints that echo one report it.
     *
     * <p>A form arrives parsed and leaves data empty, since parsing it
     * consumes the body; a part naming a file is reported apart from the
     * rest.  Anything else is reported as data, and as json when it parses.
     *
     * @param request request whose body to read
     * @param is the body
     * @return what to report about it
     * @throws IOException if reading the body fails
     */
    private static Body readBody(Request request, InputStream is)
            throws IOException {
        String contentType = request.getHeaders().get(
                HttpHeader.CONTENT_TYPE);
        if (contentType != null && contentType.startsWith(
                "multipart/form-data")) {
            JSONObject form = new JSONObject();
            JSONObject files = new JSONObject();
            try (MultiPartFormData.Parts parts = MultiPartFormData.getParts(
                    request, request, contentType, MULTI_PART_CONFIG)) {
                for (MultiPart.Part part : parts) {
                    (part.getFileName() == null ? form : files).put(
                            part.getName(), part.getContentAsString(
                                    StandardCharsets.UTF_8));
                }
            }
            return new Body(form, files, "", JSONObject.NULL);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Utils.copy(is, baos);
        String string = new String(baos.toByteArray(),
                StandardCharsets.UTF_8);
        if (contentType != null && contentType.startsWith(
                "application/x-www-form-urlencoded")) {
            Fields form = new Fields();
            UrlEncoded.decodeUtf8To(string, form);
            return new Body(mapParametersToJSON(form), new JSONObject(), "",
                    JSONObject.NULL);
        }

        Object json = JSONObject.NULL;
        try {
            json = new JSONObject(string);
        } catch (JSONException e) {
            // client can provide non-JSON data
        }
        return new Body(new JSONObject(), new JSONObject(), string, json);
    }

    /**
     * Reports a request, naming the keys upstream's get_dict would.
     *
     * <p>Which endpoint reports which keys is upstream's choice rather than
     * a pattern, so each names its own.
     *
     * @param request request to report
     * @param is the request body, read only when a key needs it
     * @param params query parameters
     * @param keys keys to report, in upstream's spelling
     * @return the report
     * @throws IOException if reading the body fails
     */
    private JSONObject describeRequest(Request request, InputStream is,
            Fields params, String... keys) throws IOException {
        Body body = null;
        for (String key : keys) {
            if (BODY_KEYS.contains(key)) {
                body = readBody(request, is);
                break;
            }
        }
        if (body == null) {
            Utils.copy(is, Utils.NULL_OUTPUT_STREAM);
        }

        JSONObject json = new JSONObject();
        for (String key : keys) {
            json.put(key, switch (key) {
            case "url" -> getFullURL(request);
            case "args" -> mapParametersToJSON(params);
            case "headers" -> hideEnv(mapFieldsToJSON(
                    request.getHeaders()), ENV_HEADERS, params);
            case "origin" -> getOrigin(request);
            case "method" -> request.getMethod();
            case "form" -> body.form();
            case "files" -> body.files();
            case "data" -> body.data();
            case "json" -> body.json();
            default -> throw new IllegalArgumentException(key);
            });
        }
        return json;
    }

    /** Whether the query asks for the headers upstream would hide. */
    private static boolean showEnv(Fields params) {
        return params.get("show_env") != null;
    }

    /**
     * Drops the names upstream hides, unless the query asks to see them.
     *
     * @param json names and values to filter
     * @param hidden names to drop, in lower case
     * @param params query parameters, which may name show_env
     * @return the same object, filtered
     */
    private static JSONObject hideEnv(JSONObject json, Set<String> hidden,
            Fields params) {
        if (showEnv(params)) {
            return json;
        }
        for (String name : new ArrayList<>(json.keySet())) {
            if (hidden.contains(name.toLowerCase(Locale.ROOT))) {
                json.remove(name);
            }
        }
        return json;
    }

    private static JSONObject mapFieldsToJSON(HttpFields fields) {
        JSONObject headers = new JSONObject();

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

    /**
     * Reports the address the request came from, as far as the proxy header
     * says.
     *
     * <p>Upstream reports X-Forwarded-For whole, chain and all, rather than
     * picking a hop out of it.
     *
     * @param request request to report
     * @return the forwarded address, or the address that connected
     */
    private static String getOrigin(Request request) {
        String forwarded = request.getHeaders().get(
                HttpHeader.X_FORWARDED_FOR);
        return forwarded != null ? forwarded :
                Request.getRemoteAddr(request);
    }

    /**
     * Reports the URL the client used, as far as the proxy headers say.
     *
     * @param request request to report
     * @return the request URL, with any forwarded scheme applied
     */
    private static String getFullURL(Request request) {
        HttpFields fields = request.getHeaders();
        String scheme = fields.get("X-Forwarded-Proto");
        if (scheme == null) {
            scheme = fields.get("X-Forwarded-Protocol");
        }
        if (scheme == null && "on".equals(fields.get("X-Forwarded-Ssl"))) {
            scheme = "https";
        }
        HttpURI uri = request.getHttpURI();
        return scheme == null ? uri.asString() :
                HttpURI.build(uri).scheme(scheme).asString();
    }

    /**
     * Splits a header that may carry a comma separated list of entity tags.
     *
     * <p>Mirrors upstream's parse_multi_value_header, which drops the quotes
     * around each tag and any weak validator prefix, so that abc, "abc" and
     * W/"abc" all name the same one.
     *
     * @param header header value, may be null
     * @return the tags the header names, empty when it names none
     */
    private static List<String> parseMultiValueHeader(String header) {
        List<String> values = new ArrayList<>();
        if (header == null || header.isEmpty()) {
            return values;
        }
        for (String part : header.split(",")) {
            String value = part.strip();
            if (value.startsWith("W/")) {
                value = value.substring("W/".length());
            }
            if (value.startsWith("\"")) {
                value = value.substring(1);
            }
            int quote = value.indexOf('"');
            values.add(quote < 0 ? value : value.substring(0, quote));
        }
        return values;
    }

    /**
     * Removes the configured prefix from a raw request path.
     *
     * <p>This mirrors Jetty's Context.getPathInContext but works on the raw
     * path, which is what the routes above match.  Requests carrying path
     * parameters or dot segments therefore do not match the prefix and fall
     * through to 404, which fails closed: raw matching is strictly narrower
     * than canonical matching, so nothing reaches a route it should not.
     *
     * @param path raw path from the request
     * @return the path beneath the prefix, or null when it lies outside
     */
    private String stripPrefix(String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        if (!path.startsWith(prefix)) {
            return null;
        }
        if (path.length() == prefix.length()) {
            return "/";
        }
        if (path.charAt(prefix.length()) != '/') {
            return null;
        }
        return path.substring(prefix.length());
    }

    private String originAndPrefix(Request request) {
        return HttpURI.build(request.getHttpURI()).path("").query(null)
                .asString() + prefix;
    }

    /**
     * Refuses a request, asking for credentials when that is what is
     * missing.
     *
     * <p>/hidden-basic-auth answers 404 instead and says nothing about
     * authentication, which is the point of it.
     *
     * @param response response to refuse with
     * @param failureStatus status this endpoint refuses with
     */
    private static void challengeBasic(Response response, int failureStatus) {
        if (failureStatus == HttpStatus.UNAUTHORIZED_401) {
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE,
                    BASIC_REALM);
        }
        response.setStatus(failureStatus);
    }

    private static void handleBasicAuth(Request request, Response response,
            OutputStream os, String suffix, int failureStatus)
            throws IOException {
        String header = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (header == null || !header.startsWith("Basic ")) {
            challengeBasic(response, failureStatus);
            return;
        }

        byte[] bytes = Base64.getDecoder().decode(
                header.substring("Basic ".length()));
        String[] parts = new String(
                bytes, StandardCharsets.UTF_8).split(":", 2);
        String[] auth = suffix.split("/", 2);
        if (auth.length != 2 || !Arrays.equals(auth, parts)) {
            challengeBasic(response, failureStatus);
            return;
        }

        JSONObject json = new JSONObject();
        json.put("authenticated", true);
        json.put("user", parts[0]);
        respondJSON(response, os, json);
    }
}
