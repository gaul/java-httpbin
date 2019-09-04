# Java httpbin

[![Build Status](https://travis-ci.org/gaul/java-httpbin.svg?branch=master)](https://travis-ci.org/gaul/java-httpbin)
[![Maven Central](https://img.shields.io/maven-central/v/org.gaul/httpbin.svg)](https://search.maven.org/#search%7Cga%7C1%7Ca%3A%22httpbin%22)

A Java-based HTTP server that lets you locally test your HTTP client, retry
logic, streaming behavior, timeouts, etc. with the endpoints of
[httpbin.org](https://httpbin.org/).

This way, you can write tests without relying on an external dependency like
httpbin.org.

## Endpoints

Java httpbin supports a subset of httpbin endpoints:

- `/ip` Returns Origin IP.
- `/user-agent` Returns user-agent.
- `/headers` Returns headers.
- `/delete` Returns DELETE data.
- `/get` Returns GET data.
- `/patch` Returns PATCH data.
- `/post` Returns POST data.
- `/put`  Returns PUT data.
- `/status/:code` Returns given HTTP Status code.
- `/redirect/:n` 302 Redirects _n_ times.
- `/relative-redirect/:n` 302 Redirects _n_ times.
- `/absolute-redirect/:n` 302 Absolute redirects _n_ times.
- `/redirect-to?url=foo` 302 Redirects to the _foo_ URL.
- `/stream/:n` Streams _n_ lines of JSON objects.
- `/stream-bytes/:n?chunkSize=c&seed=s` Streams _n_ bytes.
- `/delay/:n` Delays responding for _min(n, 10)_ seconds.
- `/bytes/:n` Generates _n_ random bytes of binary data, accepts optional _seed_ integer parameter.
- `/base64/:s` Returns a base64 decoded :s input
- `/range/:s` Return a subset of data based on Content-range header.
- `/cookies` Returns the cookies.
- `/cookies/set?name=value` Sets one or more simple cookies.
- `/cookies/delete?name` Deletes one or more simple cookies.
- `/drip?numbytes=n&duration=s&delay=s&code=code` Drips data over a duration after
  an optional initial _delay_, then optionally returns with the given status _code_.
- `/cache` Returns 200 unless an If-Modified-Since or If-None-Match header is provided, when it returns a 304.
- `/cache/:n` Sets a Cache-Control header for _n_ seconds.
- `/etag` Return 200 when If-Match or If-None-Match succeed.
- `/gzip` Returns gzip-encoded data.
- `/deflate` Returns deflate-encoded data.
- `/robots.txt` Returns some robots.txt rules.
- `/deny` Denied by robots.txt file.
- `/basic-auth/:user/:passwd` Challenges HTTP Basic Auth.
- `/hidden-basic-auth/:user/:passwd` Challenges HTTP Basic Auth and returns 404 on failure.
- `/html` Returns some HTML.
- `/xml` Returns some XML.
- `/image/png` Returns page containing a PNG image.
- `/image/jpeg` Returns page containing a JPEG image.

## Usage

First add dependency to `pom.xml`:

```xml
<dependency>
  <groupId>org.gaul</groupId>
  <artifactId>httpbin</artifactId>
  <version>1.3.0</version>
</dependency>
```

Then add to your test code:

```java
private URI httpBinEndpoint = URI.create("http://127.0.0.1:0");
private final HttpBin httpBin = new HttpBin(httpBinEndpoint);

@Before
public setUp() {
    httpBin.start();

    // reset endpoint to handle zero port
    httpBinEndpoint = new URI(httpBinEndpoint.getScheme(),
            httpBinEndpoint.getUserInfo(), httpBinEndpoint.getHost(),
            httpBin.getPort(), httpBinEndpoint.getPath(),
            httpBinEndpoint.getQuery(), httpBinEndpoint.getFragment());
}

@After
public void tearDown() throws Exception {
    httpBin.stop();
}

@Test
public void test() throws Exception {
    URI uri = URI.create(httpBinEndpoint + "/status/200");
    HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
    assert conn.getResponseCode() == 200;
}
```

## References

* [httpbin](https://httpbin.org/) - original Python implementation
* [go-httpbin](https://github.com/ahmetb/go-httpbin) - Go reimplementation

## License

Copyright (C) 2018-2019 Andrew Gaul<br />
Copyright (C) 2015-2016 Bounce Storage

Licensed under the Apache License, Version 2.0
