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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Fields;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Serves the /digest-auth endpoints.
 *
 * <p>The server keeps no state between requests: it never records the nonces
 * it issues, and recomputes the digest from whatever the client echoes back.
 * What a nonce has already been used for, and how many requests remain before
 * one goes stale, travel in cookies, so a client that drops them gets a fresh
 * challenge each time rather than an error.
 */
final class DigestAuth {
    private static final String REALM = "me@kennethreitz.com";
    private static final List<String> ALGORITHMS =
            List.of("MD5", "SHA-256", "SHA-512");
    private static final List<String> QOPS = List.of("auth", "auth-int");
    private static final List<String> TRUE_VALUES =
            List.of("1", "t", "true");
    private static final int ENTROPY_BYTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private DigestAuth() {
        throw new AssertionError("intentionally not implemented");
    }

    /**
     * Answers a request beneath /digest-auth.
     *
     * @param request request to authenticate
     * @param response response to answer with
     * @param is request body, read whether or not the digest covers it
     * @param os where a JSON answer goes
     * @param suffix path beneath /digest-auth/, naming the parameters
     * @param params query parameters
     * @param cookiePath path to scope this endpoint's cookies to
     * @throws IOException if writing the answer fails
     */
    static void handle(Request request, Response response, InputStream is,
            OutputStream os, String suffix, Fields params, String cookiePath)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Utils.copy(is, baos);
        byte[] body = baos.toByteArray();

        String[] parts = suffix.split("/", -1);
        if (parts.length < 3 || parts.length > 5) {
            response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
            return;
        }
        String user = parts[1];
        String passwd = parts[2];
        // An unrecognized algorithm or qop names no route of its own, so
        // upstream falls back rather than refusing the request.
        String algorithm = parts.length > 3 && ALGORITHMS.contains(parts[3]) ?
                parts[3] : "MD5";
        String staleAfter = parts.length > 4 ? parts[4] : "never";
        String qop = QOPS.contains(parts[0]) ? parts[0] : null;

        String requireCookieParam = params.getValue("require-cookie");
        boolean requireCookie = requireCookieParam != null &&
                TRUE_VALUES.contains(
                        requireCookieParam.toLowerCase(Locale.ROOT));

        Map<String, String> credentials = parseCredentials(
                request.getHeaders().get(HttpHeader.AUTHORIZATION));
        boolean sentCookies =
                request.getHeaders().get(HttpHeader.COOKIE) != null;
        if (credentials == null || (requireCookie && !sentCookies)) {
            challenge(request, response, qop, algorithm, /*stale=*/ false);
            setCookie(response, cookiePath, "stale_after", staleAfter);
            setCookie(response, cookiePath, "fake", "fake_value");
            return;
        }

        Map<String, String> cookies = cookiesOf(request);
        if (requireCookie && !"fake_value".equals(cookies.get("fake"))) {
            setCookie(response, cookiePath, "fake", "fake_value");
            JSONObject json = new JSONObject();
            json.put("errors", new JSONArray().put(
                    "missing cookie set on challenge"));
            HttpBinHandler.respondJSON(response, os, json,
                    HttpStatus.FORBIDDEN_403);
            return;
        }

        String nonce = credentials.getOrDefault("nonce", "");
        String staleAfterValue = cookies.get("stale_after");
        // A nonce the client has already spent, or a countdown that has run
        // out, earns a challenge marked stale: the credentials were well
        // formed, so the client should retry rather than ask for a password.
        boolean stale = (cookies.containsKey("last_nonce") &&
                Objects.equals(nonce, cookies.get("last_nonce"))) ||
                "0".equals(staleAfterValue);
        if (stale || !authenticate(request, credentials, passwd, body)) {
            challenge(request, response, qop, algorithm, stale);
            setCookie(response, cookiePath, "stale_after", staleAfter);
            setCookie(response, cookiePath, "last_nonce", nonce);
            setCookie(response, cookiePath, "fake", "fake_value");
            return;
        }

        setCookie(response, cookiePath, "fake", "fake_value");
        if (staleAfterValue != null && !staleAfterValue.isEmpty()) {
            setCookie(response, cookiePath, "stale_after",
                    nextStaleAfter(staleAfterValue));
        }
        JSONObject json = new JSONObject();
        json.put("authenticated", true);
        json.put("user", user);
        HttpBinHandler.respondJSON(response, os, json, HttpStatus.OK_200);
    }

    /**
     * Asks the client for credentials.
     *
     * <p>The stale flag reads True or False rather than the lower case
     * RFC 7616 spells, because that is what upstream's Python renders and a
     * client meeting one implementation should meet the other unchanged.
     */
    private static void challenge(Request request, Response response,
            String qop, String algorithm, boolean stale) {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        // Nothing reads a nonce back, so its contents only have to differ
        // from one challenge to the next.
        String nonce = hash(join(String.valueOf(
                        Request.getRemoteAddr(request)),
                String.valueOf(System.nanoTime()),
                HexFormat.of().formatHex(entropy)), algorithm);
        RANDOM.nextBytes(entropy);
        String opaque = hash(entropy, algorithm);

        response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE,
                ("Digest realm=\"%s\", nonce=\"%s\", opaque=\"%s\", " +
                        "qop=\"%s\", algorithm=%s, stale=%s").formatted(
                        REALM, nonce, opaque,
                        qop == null ? "auth, auth-int" : qop, algorithm,
                        stale ? "True" : "False"));
        response.setStatus(HttpStatus.UNAUTHORIZED_401);
    }

    /** Recomputes the digest the client sent and says whether it agrees. */
    private static boolean authenticate(Request request,
            Map<String, String> credentials, String passwd, byte[] body) {
        String username = credentials.get("username");
        String expected = credentials.get("response");
        if (username == null || expected == null) {
            return false;
        }
        // The client names the algorithm and realm it hashed with; the
        // password comes from the path, and is the only secret involved.
        String algorithm = credentials.get("algorithm");
        String ha1 = hash(join(username,
                credentials.getOrDefault("realm", ""), passwd), algorithm);

        String qop = credentials.get("qop");
        String method = request.getMethod();
        String uri = requestUri(request);
        String ha2;
        if (qop == null || qop.equals("auth")) {
            ha2 = hash(join(method, uri), algorithm);
        } else if (qop.equals("auth-int")) {
            ha2 = hash(join(method, uri, hash(body, algorithm)), algorithm);
        } else {
            return false;
        }

        String nonce = credentials.getOrDefault("nonce", "");
        String digest;
        if (qop == null) {
            digest = hash(join(ha1, nonce, ha2), algorithm);
        } else {
            String nc = credentials.get("nc");
            String cnonce = credentials.get("cnonce");
            if (nc == null || cnonce == null) {
                return false;
            }
            digest = hash(join(ha1, nonce, nc, cnonce, qop, ha2), algorithm);
        }
        return digest.equals(expected);
    }

    /**
     * Reads the parameters of a Digest Authorization header.
     *
     * @param header header value, may be null
     * @return the parameters by lower case name, or null if the header names
     *         no digest credentials
     */
    private static Map<String, String> parseCredentials(String header) {
        if (header == null) {
            return null;
        }
        int space = header.indexOf(' ');
        if (space < 0 || !header.substring(0, space).equalsIgnoreCase(
                "Digest")) {
            return null;
        }

        Map<String, String> credentials = new HashMap<>();
        for (String parameter : splitParameters(header.substring(space + 1))) {
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                continue;
            }
            credentials.put(
                    parameter.substring(0, equals).strip().toLowerCase(
                            Locale.ROOT),
                    unquote(parameter.substring(equals + 1).strip()));
        }
        return credentials;
    }

    /** Splits on the commas between parameters, not those inside a value. */
    private static List<String> splitParameters(String parameters) {
        List<String> split = new ArrayList<>();
        StringBuilder parameter = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < parameters.length(); ++i) {
            char c = parameters.charAt(i);
            if (quoted && c == '\\' && i + 1 < parameters.length()) {
                parameter.append(c).append(parameters.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
                parameter.append(c);
            } else if (c == ',' && !quoted) {
                split.add(parameter.toString());
                parameter.setLength(0);
            } else {
                parameter.append(c);
            }
        }
        split.add(parameter.toString());
        return split;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || !value.startsWith("\"") ||
                !value.endsWith("\"")) {
            return value;
        }
        return value.substring(1, value.length() - 1)
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Counts down towards the 0 that makes the next nonce stale. */
    private static String nextStaleAfter(String staleAfter) {
        try {
            return String.valueOf(Integer.parseInt(staleAfter) - 1);
        } catch (NumberFormatException nfe) {
            return "never";
        }
    }

    private static String requestUri(Request request) {
        HttpURI uri = request.getHttpURI();
        String query = uri.getQuery();
        return query == null || query.isEmpty() ?
                uri.getPath() : uri.getPath() + "?" + query;
    }

    private static Map<String, String> cookiesOf(Request request) {
        Map<String, String> cookies = new HashMap<>();
        for (HttpCookie cookie : Request.getCookies(request)) {
            cookies.putIfAbsent(cookie.getName(), cookie.getValue());
        }
        return cookies;
    }

    private static void setCookie(Response response, String cookiePath,
            String name, String value) {
        response.getHeaders().add(HttpHeader.SET_COOKIE,
                "%s=%s; Path=%s".formatted(name, value, cookiePath));
    }

    private static byte[] join(String... parts) {
        return String.join(":", parts).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Hashes with the named algorithm, MD5 standing in for anything else.
     *
     * <p>A client naming none is answered in MD5, which is what upstream's
     * hash helper falls back to.
     */
    private static String hash(byte[] data, String algorithm) {
        String named = algorithm != null && ALGORITHMS.contains(algorithm) ?
                algorithm : "MD5";
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(named).digest(data));
        } catch (NoSuchAlgorithmException nsae) {
            throw new IllegalStateException(nsae);
        }
    }
}
