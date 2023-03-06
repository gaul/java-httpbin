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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Reimplementation of HttpBin https://httpbin.org/ suitable for offline unit
 * tests.
 */
public final class HttpBin {
    private final Server server;
    private final int mHTTPPort;
    private final int mHTTPsPort;

    public HttpBin(String ip, int httpPort, int httpsPort, String keystore) throws Exception {
        this(ip, httpPort, httpsPort, keystore, new HttpBinHandler());
    }

    public HttpBin(String ip, int httpPort, int httpsPort, String keystore, HttpBinHandler handler) throws Exception {

        server = new Server();
        HttpConnectionFactory httpConnectionFactory =
                new HttpConnectionFactory();

        mHTTPPort = httpPort;
        mHTTPsPort = httpsPort;
        List<Connector> connectors = new ArrayList<Connector>();
        if (httpPort != 0) {
            ServerConnector connector = new ServerConnector(server,
                httpConnectionFactory);
            connector.setHost(ip);
            connector.setPort(httpPort);
            connectors.add(connector);
        }

        if (httpsPort != 0) {
            HttpConfiguration https = new HttpConfiguration();
            SecureRequestCustomizer src = new SecureRequestCustomizer();
            src.setSniHostCheck(false);
            https.addCustomizer(src);
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(keystore);
            sslContextFactory.setKeyStorePassword("123456");
            sslContextFactory.setKeyManagerPassword("123456");

            ServerConnector sslConnector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                new HttpConnectionFactory(https));
            sslConnector.setHost(ip);
            sslConnector.setPort(httpsPort);
            connectors.add(sslConnector);
        }

        if (connectors.size() == 0) {
            throw new Exception("At least one of ports must be set");
        }
        Connector[] customs = new Connector[connectors.size()];
        connectors.toArray(customs);
        server.setConnectors(customs);
        server.setHandler(handler);
    }

    public void start() throws Exception {
        server.start();
    }

    public void stop() throws Exception {
        server.stop();
    }

    public int getHTTPPort() {
        return mHTTPPort;
    }

    public int getHTTPsPort() {
        return mHTTPsPort;
    }
}
