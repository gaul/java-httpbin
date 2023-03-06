/*
 * Copyright 2018-2019 Andrew Gaul <andrew@gaul.org>
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

import java.net.URI;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.MultiPartContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
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
        httpBin = new HttpBin("127.0.0.1", 8001, 0, "");
        httpBin.start();

        // reset endpoint to handle zero port
        httpBinEndpoint = new URI(httpBinEndpoint.getScheme(),
                httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
                httpBin.getHTTPPort(), httpBinEndpoint.getPath(),
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
                .content(new StringContentProvider(input), "application/json")
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
        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart("field1", new StringContentProvider("foo"),
                null);
        multiPart.addFieldPart("field2", new StringContentProvider("bar"),
                null);
        multiPart.close();

        ContentResponse response = client.POST(httpBinEndpoint + "/post")
                .content(multiPart)
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getJSONObject("form").similar(input)).isTrue();
    }

    @Test
    public void testPutData() throws Exception {
        String input = "{\"foo\": 42}";
        ContentResponse response = client.newRequest(httpBinEndpoint + "/put")
                .method("PUT")
                .content(new StringContentProvider(input), "application/json")
                .send();
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        JSONObject object = new JSONObject(response.getContentAsString());
        assertThat(object.getString("data")).isEqualTo(input);
    }
}
