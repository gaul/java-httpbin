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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.brotli.dec.BrotliInputStream;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MultiPart;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpBinTest {
    private static final Logger logger = LoggerFactory.getLogger(
            HttpBinTest.class);

    private URI httpBinEndpoint = URI.create("http://127.0.0.1:0");

    private HttpBin httpBin;
    private HttpClient client;

    @Before
    public void setUp() throws Exception {
        httpBin = new HttpBin(httpBinEndpoint);
        httpBin.start();

        // reset endpoint to handle zero port
        httpBinEndpoint = new URI(httpBinEndpoint.getScheme(),
                httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
                httpBin.getPort(), httpBinEndpoint.getPath(),
                httpBinEndpoint.getQuery(), httpBinEndpoint.getFragment());
        logger.debug("HttpBin listening on {}", httpBinEndpoint);

        client = new HttpClient();
        client.start();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (httpBin != null) {
            httpBin.stop();
        }
    }

    @Test
    public void testPostData() throws Exception {
        String input = "{\"foo\": 42}";
        ContentResponse response = client.POST(httpBinEndpoint + "/post")
                .body(new StringRequestContent("application/json", input))
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getString("data")).isEqualTo(input);
    }

    @Test
    public void testPostDataMultipartContent() throws Exception {
        JSONObject input = new JSONObject();
        input.put("field1", "foo");
        input.put("field2", "bar");
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("field1",
                /*fileName=*/ null, /*fields=*/ null,
                new StringRequestContent("foo")));
        multiPart.addPart(new MultiPart.ContentSourcePart("field2",
                /*fileName=*/ null, /*fields=*/ null,
                new StringRequestContent("bar")));
        multiPart.close();

        ContentResponse response = client.POST(httpBinEndpoint + "/post")
                .body(multiPart)
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getJSONObject("form").similar(input)).isTrue();
    }

    @Test
    public void testBrotli() throws Exception {
        ContentResponse response = client.GET(httpBinEndpoint + "/brotli");
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING))
                .isEqualTo("br");

        byte[] body;
        try (BrotliInputStream is = new BrotliInputStream(
                new ByteArrayInputStream(response.getContent()))) {
            body = is.readAllBytes();
        }
        JSONObject json = new JSONObject(
                new String(body, StandardCharsets.UTF_8));
        assertThat(json.getBoolean("brotli")).isTrue();
        assertThat(json.getString("method")).isEqualTo("GET");
        assertThat(json.getJSONObject("headers")).isNotNull();
    }

    /** Bytes a client cannot classify are bytes it cannot use. */
    @Test
    public void testContentTypes() throws Exception {
        assertContentType("/bytes/16", "application/octet-stream");
        assertContentType("/stream-bytes/16", "application/octet-stream");
        assertContentType("/range/26", "application/octet-stream");
        assertContentType("/drip?numbytes=4&duration=0",
                "application/octet-stream");
        assertContentType("/base64/aGVsbG8=", "text/html;charset=utf-8");
    }

    private void assertContentType(String path, String contentType)
            throws Exception {
        ContentResponse response = client.GET(httpBinEndpoint + path);
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_TYPE))
                .as(path).isEqualTo(contentType);
    }

    /** A HEAD answers what a GET would, with the length but no body. */
    @Test
    public void testHead() throws Exception {
        for (String path : new String[] {
            "/get", "/headers", "/ip", "/html", "/image/png", "/robots.txt",
        }) {
            ContentResponse get = client.GET(httpBinEndpoint + path);
            ContentResponse head = client.newRequest(httpBinEndpoint + path)
                    .method("HEAD")
                    .send();
            assertThat(head.getStatus()).as(path).isEqualTo(get.getStatus());
            assertThat(head.getContent()).as(path).isEmpty();
            assertThat(head.getHeaders().get(HttpHeader.CONTENT_LENGTH))
                    .as(path).isEqualTo(get.getHeaders().get(
                            HttpHeader.CONTENT_LENGTH));
        }
    }

    /** Upstream reports the forwarded address whole, chain and all. */
    @Test
    public void testOriginFollowsForwardedFor() throws Exception {
        ContentResponse response = client.GET(httpBinEndpoint + "/ip");
        assertThat(new JSONObject(response.getContentAsString())
                .getString("origin")).as("no header").isEqualTo("127.0.0.1");

        for (String path : new String[] {"/ip", "/get", "/anything"}) {
            response = client.newRequest(httpBinEndpoint + path)
                    .headers(fields -> fields.put(
                            HttpHeader.X_FORWARDED_FOR,
                            "203.0.113.9, 198.51.100.1"))
                    .send();
            assertThat(new JSONObject(response.getContentAsString())
                    .getString("origin")).as(path)
                    .isEqualTo("203.0.113.9, 198.51.100.1");
        }
    }

    @Test
    public void testCorsHeaders() throws Exception {
        HttpFields headers = client.GET(httpBinEndpoint + "/get")
                .getHeaders();
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("*");
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        // Only a preflight asks about these.
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS))
                .isNull();

        headers = client.newRequest(httpBinEndpoint + "/get")
                .headers(fields -> fields.put(HttpHeader.ORIGIN,
                        "https://example.com"))
                .send()
                .getHeaders();
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("https://example.com");
    }

    /** Upstream's hook reaches error responses, so this one does too. */
    @Test
    public void testCorsHeadersOnUnknownPath() throws Exception {
        ContentResponse response = client.GET(
                httpBinEndpoint + "/nonexistent");
        assertThat(response.getStatus()).as("status").isEqualTo(404);
        assertThat(response.getHeaders().get(
                HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
    }

    @Test
    public void testOptionsAnswersPreflight() throws Exception {
        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/get")
                .method("OPTIONS")
                .headers(fields -> fields.put(
                        HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS,
                        "X-Test-Header"))
                .send();
        // A browser discards a preflight that does not succeed.
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        assertThat(response.getContent()).isEmpty();

        HttpFields headers = response.getHeaders();
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS))
                .isEqualTo("GET, POST, PUT, DELETE, PATCH, OPTIONS");
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_MAX_AGE))
                .isEqualTo("3600");
        assertThat(headers.get(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS))
                .isEqualTo("X-Test-Header");
        // /get answers a GET and what every route answers, and no more.
        assertThat(headers.get(HttpHeader.ALLOW))
                .isEqualTo("GET, HEAD, OPTIONS");
    }

    /**
     * A path whose number does not parse names no route.
     *
     * <p>Each of these used to answer an empty 200: the parse threw after
     * the response had been committed, so nothing could say otherwise.
     */
    @Test
    public void testUnparseablePathIsNotFound() throws Exception {
        for (String path : new String[] {
            "/bytes/abc", "/cache/abc", "/redirect/abc", "/range/abc",
            "/links/abc", "/stream/abc", "/absolute-redirect/abc",
        }) {
            assertThat(client.GET(httpBinEndpoint + path).getStatus())
                    .as(path).isEqualTo(404);
        }
    }

    /** A method a path does not answer is refused, naming what it does. */
    @Test
    public void testMethodNotAllowed() throws Exception {
        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/get")
                .method("POST")
                .body(new StringRequestContent("text/plain", "x"))
                .send();
        assertThat(response.getStatus()).as("POST /get").isEqualTo(405);
        assertThat(response.getHeaders().get(HttpHeader.ALLOW))
                .isEqualTo("GET, HEAD, OPTIONS");

        response = client.newRequest(httpBinEndpoint + "/post")
                .method("OPTIONS")
                .send();
        assertThat(response.getStatus()).as("OPTIONS /post").isEqualTo(200);
        assertThat(response.getHeaders().get(HttpHeader.ALLOW))
                .isEqualTo("POST, OPTIONS");

        // A preflight for a path nothing serves is refused like any other.
        response = client.newRequest(httpBinEndpoint + "/nonexistent")
                .method("OPTIONS")
                .send();
        assertThat(response.getStatus()).as("OPTIONS unknown").isEqualTo(404);
    }

    /**
     * The upstream suite cannot reach this path: its own implementation
     * fails on any request carrying a query string, and require-cookie
     * needs one.
     */
    @Test
    public void testDigestAuthRequiresCookie() throws Exception {
        String uri = httpBinEndpoint +
                "/digest-auth/auth/user/passwd/MD5?require-cookie=true";
        ContentResponse response = client.GET(uri);
        assertThat(response.getStatus()).as("challenge").isEqualTo(401);
        assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE))
                .contains("Digest realm=");

        // The challenge just set the cookie it wants to see, so take it away
        // again.  A cookie reaches this client through its store, which
        // writes the Cookie header itself.
        putCookie("other", "1");
        response = sendWrongCredentials(uri);
        assertThat(response.getStatus()).as("cookie missing").isEqualTo(403);
        JSONObject json = new JSONObject(response.getContentAsString());
        assertThat(json.getJSONArray("errors").getString(0))
                .isEqualTo("missing cookie set on challenge");

        // The cookie carries the request as far as the password, which is
        // still wrong.
        putCookie("fake", "fake_value");
        response = sendWrongCredentials(uri);
        assertThat(response.getStatus()).as("cookie sent").isEqualTo(401);
    }

    private void putCookie(String name, String value) {
        client.getHttpCookieStore().clear();
        client.getHttpCookieStore().add(httpBinEndpoint,
                HttpCookie.from(name, value));
    }

    private ContentResponse sendWrongCredentials(String uri) throws Exception {
        return client.newRequest(uri)
                .headers(fields -> fields.put(HttpHeader.AUTHORIZATION,
                        "Digest username=\"user\", response=\"wrong\", " +
                                "nonce=\"wrong\""))
                .send();
    }

    /** Upstream answers some status codes with more than a status. */
    @Test
    public void testStatusCarriesWhatUpstreamSends() throws Exception {
        for (int code : new int[] {301, 302, 303, 305, 307}) {
            assertThat(status(code).getHeaders().get(HttpHeader.LOCATION))
                    .as("%d", code).isEqualTo("/redirect/1");
        }
        // These are not in upstream's table, so they name no location.
        for (int code : new int[] {300, 304, 306, 308}) {
            assertThat(status(code).getHeaders().get(HttpHeader.LOCATION))
                    .as("%d", code).isNull();
        }

        assertThat(status(401).getHeaders().get(HttpHeader.WWW_AUTHENTICATE))
                .isEqualTo("Basic realm=\"Fake Realm\"");
        assertThat(status(407).getHeaders().get(HttpHeader.PROXY_AUTHENTICATE))
                .isEqualTo("Basic realm=\"Fake Realm\"");

        ContentResponse response = status(402);
        assertThat(response.getContentAsString())
                .isEqualTo("Fuck you, pay me!");
        assertThat(response.getHeaders().get("x-more-info"))
                .isEqualTo("http://vimeo.com/22053820");

        response = status(418);
        assertThat(response.getContentAsString()).contains("-=[ teapot ]=-")
                .contains("`\"\"\"`");
        assertThat(response.getHeaders().get("x-more-info"))
                .isEqualTo("http://tools.ietf.org/html/rfc2324");

        assertThat(new JSONObject(status(406).getContentAsString())
                .getJSONArray("accept").length()).isEqualTo(5);
    }

    private ContentResponse status(int code) throws Exception {
        return client.newRequest(httpBinEndpoint + "/status/" + code)
                .followRedirects(false)
                .send();
    }

    @Test
    public void testConditionalEndpoints() throws Exception {
        // /cache names the thing a later request would ask about.
        ContentResponse response = client.GET(httpBinEndpoint + "/cache");
        assertThat(response.getHeaders().get(HttpHeader.ETAG)).hasSize(32);
        assertThat(response.getHeaders().get(HttpHeader.LAST_MODIFIED))
                .isNotNull();

        // /etag answers with what /get would, plus the tag.
        response = client.GET(httpBinEndpoint + "/etag/abc");
        assertThat(response.getHeaders().get(HttpHeader.ETAG))
                .isEqualTo("abc");
        assertThat(new JSONObject(response.getContentAsString()).keySet())
                .containsExactlyInAnyOrder("url", "args", "headers", "origin");

        // A challenge says what it wants; the hidden one says nothing.
        response = client.GET(httpBinEndpoint + "/basic-auth/user/passwd");
        assertThat(response.getStatus()).as("basic").isEqualTo(401);
        assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE))
                .isEqualTo("Basic realm=\"Fake Realm\"");
        response = client.GET(
                httpBinEndpoint + "/hidden-basic-auth/user/passwd");
        assertThat(response.getStatus()).as("hidden").isEqualTo(404);
        assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE))
                .isNull();
    }

    /** Upstream delays whichever method asks it to. */
    @Test
    public void testDelayAnswersEveryMethod() throws Exception {
        for (String method : new String[] {
            "GET", "POST", "PUT", "DELETE", "PATCH",
        }) {
            ContentResponse response = client.newRequest(
                    httpBinEndpoint + "/delay/0")
                    .method(method)
                    .send();
            assertThat(response.getStatus()).as(method).isEqualTo(200);
        }
    }

    @Test
    public void testUuid() throws Exception {
        ContentResponse response = client.GET(httpBinEndpoint + "/uuid");
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        String uuid = new JSONObject(response.getContentAsString())
                .getString("uuid");
        assertThat(UUID.fromString(uuid)).hasToString(uuid);
        assertThat(new JSONObject(client.GET(httpBinEndpoint + "/uuid")
                .getContentAsString()).getString("uuid")).isNotEqualTo(uuid);
    }

    /** Upstream reads the Accept header rather than negotiating over it. */
    @Test
    public void testImageAccepted() throws Exception {
        assertImage("image/webp", "image/webp");
        assertImage("image/svg+xml", "image/svg+xml");
        assertImage("image/jpeg", "image/jpeg");
        assertImage("image/png", "image/png");
        assertImage("image/*", "image/png");
        assertImage("IMAGE/WEBP", "image/webp");
        assertImage("text/html,image/jpeg;q=0.9", "image/jpeg");

        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/image")
                .headers(fields -> fields.put(HttpHeader.ACCEPT, "text/plain"))
                .send();
        assertThat(response.getStatus()).as("unsupported").isEqualTo(406);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getString("message"))
                .isEqualTo("Client did not request a supported media type.");
        assertThat(object.getJSONArray("accept").length()).isEqualTo(5);
    }

    private void assertImage(String accept, String contentType)
            throws Exception {
        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/image")
                .headers(fields -> fields.put(HttpHeader.ACCEPT, accept))
                .send();
        assertThat(response.getStatus()).as(accept).isEqualTo(200);
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_TYPE))
                .as(accept).isEqualTo(contentType);
        assertThat(response.getContent()).as(accept).isNotEmpty();
    }

    @Test
    public void testLinks() throws Exception {
        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/links/3")
                .followRedirects(false)
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(302);
        assertThat(response.getHeaders().get(HttpHeader.LOCATION))
                .isEqualTo("/links/3/0");

        assertThat(client.GET(httpBinEndpoint + "/links/3/1")
                .getContentAsString()).isEqualTo(
                        "<html><head><title>Links</title></head><body>" +
                        "<a href='/links/3/0'>0</a> 1 " +
                        "<a href='/links/3/2'>2</a> </body></html>");

        // Upstream serves between one and two hundred links, whatever it is
        // asked for.
        assertThat(client.GET(httpBinEndpoint + "/links/0/0")
                .getContentAsString()).contains("<body>0 </body>");
        assertThat(client.GET(httpBinEndpoint + "/links/500/0")
                .getContentAsString()).contains("/links/200/199");
    }

    /** A part naming a file is reported apart from the rest. */
    @Test
    public void testPostFileIsReportedSeparately() throws Exception {
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("field",
                /*fileName=*/ null, /*fields=*/ null,
                new StringRequestContent("value")));
        multiPart.addPart(new MultiPart.ContentSourcePart("upload",
                "name.txt", /*fields=*/ null,
                new StringRequestContent("file body")));
        multiPart.close();

        ContentResponse response = client.POST(httpBinEndpoint + "/post")
                .body(multiPart)
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getJSONObject("form").getString("field"))
                .isEqualTo("value");
        assertThat(object.getJSONObject("form").has("upload")).isFalse();
        assertThat(object.getJSONObject("files").getString("upload"))
                .isEqualTo("file body");
        assertThat(object.getString("data")).isEmpty();
        assertThat(object.isNull("json")).isTrue();
    }

    /** Each endpoint reports the keys upstream chose for it, and no more. */
    @Test
    public void testEchoedKeys() throws Exception {
        assertKeys("/get", "url", "args", "headers", "origin");
        assertKeys("/anything", "url", "args", "headers", "origin", "method",
                "form", "data", "files", "json");
        assertKeys("/gzip", "origin", "headers", "method", "gzipped");
    }

    private void assertKeys(String path, String... keys) throws Exception {
        // The client decodes /gzip for us, so every body arrives as JSON.
        ContentResponse response = client.GET(httpBinEndpoint + path);
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.keySet()).as(path)
                .containsExactlyInAnyOrder(keys);
    }

    @Test
    public void testPutData() throws Exception {
        String input = "{\"foo\": 42}";
        ContentResponse response = client.newRequest(httpBinEndpoint + "/put")
                .method("PUT")
                .body(new StringRequestContent("application/json", input))
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getString("data")).isEqualTo(input);
    }
}
