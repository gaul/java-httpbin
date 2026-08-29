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
        assertThat(response.getStatus()).as("status").isEqualTo(501);
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
        assertThat(headers.get(HttpHeader.ALLOW))
                .isEqualTo("GET, POST, PUT, DELETE, PATCH, OPTIONS");
    }

    /**
     * A preflight names a path the browser has not fetched yet, so answering
     * only the paths this server serves would need a route table it does not
     * have.  Every path succeeds instead.
     */
    @Test
    public void testOptionsSucceedsForAnyPath() throws Exception {
        ContentResponse response = client.newRequest(
                httpBinEndpoint + "/nonexistent")
                .method("OPTIONS")
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
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
