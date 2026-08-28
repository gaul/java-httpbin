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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.assertj.core.api.Assertions;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class HttpBinPrefixTest {
    private static final String PREFIX = "/some/other/path";

    private URI httpBinEndpoint = URI.create("http://127.0.0.1:0" + PREFIX);

    private HttpBin httpBin;
    private HttpClient client;
    private String base;

    @Before
    public void setUp() throws Exception {
        httpBin = new HttpBin(httpBinEndpoint);
        httpBin.start();

        // reset endpoint to handle zero port
        httpBinEndpoint = new URI(httpBinEndpoint.getScheme(),
                httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
                httpBin.getPort(), httpBinEndpoint.getPath(),
                httpBinEndpoint.getQuery(), httpBinEndpoint.getFragment());
        base = "http://127.0.0.1:" + httpBin.getPort();

        client = new HttpClient();
        client.setFollowRedirects(false);
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
    public void testNormalizePrefix() throws Exception {
        assertThat(HttpBinHandler.normalizePrefix(null)).isEmpty();
        assertThat(HttpBinHandler.normalizePrefix("")).isEmpty();
        assertThat(HttpBinHandler.normalizePrefix("/")).isEmpty();
        assertThat(HttpBinHandler.normalizePrefix("/pfx")).isEqualTo("/pfx");
        assertThat(HttpBinHandler.normalizePrefix("/pfx/")).isEqualTo("/pfx");
        assertThat(HttpBinHandler.normalizePrefix("/a/b/")).isEqualTo("/a/b");

        for (String invalid : new String[] {
            "pfx", "//", "/a//b", "/a%20b", "/a;b", "/a?b", "/a#b",
            "/.", "/..", "/a/./b", "/a/../b",
        }) {
            Assertions.assertThatThrownBy(() -> HttpBinHandler.normalizePrefix(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void testEndpointAndHandlerPrefixMustAgree() throws Exception {
        Assertions.assertThatThrownBy(() -> new HttpBin(
                URI.create("http://127.0.0.1:0/pfx"), new HttpBinHandler()))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> new HttpBin(
                URI.create("http://127.0.0.1:0"), new HttpBinHandler("/pfx")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testPrefixedEndpoint() throws Exception {
        ContentResponse response = client.GET(base + PREFIX + "/get");
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getString("url"))
                .isEqualTo(base + PREFIX + "/get");
    }

    @Test
    public void testRootIsNotServed() throws Exception {
        assertThat(client.GET(base + "/get").getStatus()).isEqualTo(501);
        assertThat(client.GET(base + "/headers").getStatus()).isEqualTo(501);
    }

    @Test
    public void testBarePrefixServesHomePage() throws Exception {
        assertThat(client.GET(base + PREFIX).getStatus()).isEqualTo(200);
        assertThat(client.GET(base + PREFIX + "/").getStatus()).isEqualTo(200);
    }

    @Test
    public void testRedirectLocationsCarryPrefix() throws Exception {
        assertThat(location("/status/302")).isEqualTo(PREFIX + "/redirect/1");
        assertThat(location("/redirect/3"))
                .isEqualTo(PREFIX + "/relative-redirect/2");
        assertThat(location("/redirect/1")).isEqualTo(PREFIX + "/get");
        assertThat(location("/relative-redirect/2"))
                .isEqualTo(PREFIX + "/relative-redirect/1");
        assertThat(location("/redirect/3?absolute=true"))
                .isEqualTo(base + PREFIX + "/absolute-redirect/2");
        assertThat(location("/absolute-redirect/2"))
                .isEqualTo(base + PREFIX + "/absolute-redirect/1");
        assertThat(location("/absolute-redirect/1"))
                .isEqualTo(base + PREFIX + "/get");
        assertThat(location("/cookies/set?name=value"))
                .isEqualTo(PREFIX + "/cookies");
    }

    /** Redirects to a caller-supplied URL must be emitted verbatim. */
    @Test
    public void testRedirectToIsNotPrefixed() throws Exception {
        assertThat(location("/redirect-to?url=/get")).isEqualTo("/get");
        assertThat(location("/redirect-to?url=http%3A%2F%2Fexample.com%2F"))
                .isEqualTo("http://example.com/");
    }

    @Test
    public void testRedirectChainsResolve() throws Exception {
        client.setFollowRedirects(true);
        for (String path : new String[] {
            "/redirect/3", "/redirect/3?absolute=true",
            "/relative-redirect/3", "/absolute-redirect/3",
        }) {
            ContentResponse response = client.GET(base + PREFIX + path);
            assertThat(response.getStatus()).as(path).isEqualTo(200);
            JSONObject object = new JSONObject(response.getContentAsString());
            assertThat(object.getString("url")).as(path)
                    .isEqualTo(base + PREFIX + "/get");
        }
    }

    @Test
    public void testCookieRoundTrip() throws Exception {
        client.setFollowRedirects(true);
        client.GET(base + PREFIX + "/cookies/set?name=value");

        ContentResponse response = client.GET(base + PREFIX + "/cookies");
        JSONObject cookies = new JSONObject(response.getContentAsString())
                .getJSONObject("cookies");
        assertThat(cookies.getString("name")).isEqualTo("value");

        client.GET(base + PREFIX + "/cookies/delete?name");
        response = client.GET(base + PREFIX + "/cookies");
        cookies = new JSONObject(response.getContentAsString())
                .getJSONObject("cookies");
        assertThat(cookies.optString("name")).isEmpty();
    }

    /** The cookie must be scoped to the prefix, not to the whole origin. */
    @Test
    public void testCookiePathIsPrefix() throws Exception {
        ContentResponse response = client.GET(
                base + PREFIX + "/cookies/set?name=value");
        assertThat(response.getHeaders().get(HttpHeader.SET_COOKIE))
                .isEqualTo("name=value; Path=" + PREFIX);
    }

    @Test
    public void testRobotsTxtNamesPrefixedPath() throws Exception {
        ContentResponse response = client.GET(base + PREFIX + "/robots.txt");
        assertThat(response.getContentAsString())
                .isEqualTo("User-agent: *\nDisallow: " + PREFIX + "/deny\n");
    }

    /**
     * Exercises paths that a client would otherwise normalize away.
     *
     * <p>Matching uses the raw path, so path parameters and dot segments do
     * not match the prefix.  This fails closed and is asserted so that the
     * behavior is deliberate rather than accidental.
     */
    @Test
    public void testRawPaths() throws Exception {
        assertThat(rawStatus(PREFIX + "/get")).isEqualTo(200);
        assertThat(rawStatus(PREFIX)).isEqualTo(200);
        assertThat(rawStatus(PREFIX + "/")).isEqualTo(200);
        assertThat(rawStatus(PREFIX + "foo")).isEqualTo(501);
        assertThat(rawStatus("/some/other/pat")).isEqualTo(501);
        assertThat(rawStatus("/get")).isEqualTo(501);
        assertThat(rawStatus("/SOME/OTHER/PATH/get")).isEqualTo(501);
        assertThat(rawStatus(PREFIX + "/get;jsessionid=1")).isEqualTo(501);
        assertThat(rawStatus(PREFIX + "/.." + PREFIX + "/get"))
                .isEqualTo(501);
        assertThat(rawStatus(PREFIX + "/.")).isEqualTo(501);
        assertThat(rawStatus(PREFIX + "/..")).isEqualTo(501);
        // Jetty rejects an empty segment before the handler sees it.
        assertThat(rawStatus(PREFIX + "//get")).isEqualTo(400);
        // Asterisk-form is only legal for OPTIONS.
        assertThat(rawStatus("OPTIONS", "*")).isEqualTo(501);
    }

    private String location(String path) throws Exception {
        ContentResponse response = client.GET(base + PREFIX + path);
        return response.getHeaders().get(HttpHeader.LOCATION);
    }

    /** Sends a request line verbatim, bypassing client-side normalization. */
    private int rawStatus(String target) throws Exception {
        return rawStatus("GET", target);
    }

    private int rawStatus(String method, String target) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", httpBin.getPort())) {
            OutputStream os = socket.getOutputStream();
            os.write((method + " " + target + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n").getBytes(
                            StandardCharsets.UTF_8));
            os.flush();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String status = reader.readLine();
                return Integer.parseInt(status.split(" ", 3)[1]);
            }
        }
    }
}
