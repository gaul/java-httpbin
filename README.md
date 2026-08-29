# Java httpbin

[![Build Status](https://github.com/gaul/java-httpbin/actions/workflows/ci.yml/badge.svg)](https://github.com/gaul/java-httpbin/actions/workflows/ci.yml)
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
- `/anything` Returns anything passed in request data.
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
- `/cookies/set/:name/:value` Sets one simple cookie.
- `/cookies/delete?name` Deletes one or more simple cookies.
- `/drip?numbytes=n&duration=s&delay=s&code=code` Drips data over a duration after
  an optional initial _delay_, then optionally returns with the given status _code_.
- `/cache` Returns 200 unless an If-Modified-Since or If-None-Match header is provided, when it returns a 304.
- `/cache/:n` Sets a Cache-Control header for _n_ seconds.
- `/etag` Return 200 when If-Match or If-None-Match succeed.
- `/response-headers?key=value` Sets the given response headers and returns them as JSON.
- `/gzip` Returns gzip-encoded data.
- `/deflate` Returns deflate-encoded data.
- `/brotli` Returns Brotli-encoded data.
- `/robots.txt` Returns some robots.txt rules.
- `/deny` Denied by robots.txt file.
- `/basic-auth/:user/:passwd` Challenges HTTP Basic Auth.
- `/hidden-basic-auth/:user/:passwd` Challenges HTTP Basic Auth and returns 404 on failure.
- `/bearer` Challenges HTTP Bearer Auth and returns the token.
- `/digest-auth/:qop/:user/:passwd[/:algorithm[/:stale_after]]` Challenges HTTP Digest Auth.
- `/html` Returns some HTML.
- `/xml` Returns some XML.
- `/image/png` Returns page containing a PNG image.
- `/image/jpeg` Returns page containing a JPEG image.

`/brotli` answers with a valid Brotli stream that stores its bytes rather
than compressing them: the JDK ships no Brotli encoder, and the ones on offer
bind to a native library that everything depending on this library would then
have to carry.

Every response carries the CORS headers httpbin sends.  `OPTIONS` answers a
preflight with the 200 a browser requires before it will send the request
itself; a preflight names a path the browser has not fetched yet, so any path
answers one, not only the endpoints above.

## Usage

First add dependency to `pom.xml`:

```xml
<dependency>
  <groupId>org.gaul</groupId>
  <artifactId>httpbin</artifactId>
  <version>1.4.0</version>
</dependency>
```

Then add to your test code:

```java
private URI httpBinEndpoint = URI.create("http://127.0.0.1:0");
private final HttpBin httpBin = new HttpBin(httpBinEndpoint);

@Before
public void setUp() throws Exception {
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

## Path prefix

By default the endpoints live at the server root.  Give the endpoint URI a path
to serve them beneath it instead, so that a test can share an origin with
another service:

```java
URI httpBinEndpoint = URI.create("http://127.0.0.1:0/some/other/path");
HttpBin httpBin = new HttpBin(httpBinEndpoint);
httpBin.start();

// GET /some/other/path/headers returns the headers
// GET /headers                 returns 501
```

The executable jar accepts the same URI:

```
httpbin http://127.0.0.1:8080/some/other/path
```

Notes:

- Requests outside the prefix return 501, as unknown paths already do.
- The prefix must be a plain path: no percent-encoding, `;`, `?`, `#`, or empty
  or dot segments.  Requests are matched against the raw path, so a prefix
  needing decoding could never match, and an invalid one is rejected outright
  rather than silently serving nothing.
- For the same reason, a prefixed request carrying path parameters
  (`/some/other/path/get;jsessionid=1`) or dot segments does not match and
  returns 501.
- `Location` headers this server generates, and the `Path` of cookies it sets,
  carry the prefix.  `/redirect-to?url=` and `/response-headers` echo values the
  caller supplied and are left verbatim, so a caller wanting those prefixed
  must say so.
- When passing your own handler to `new HttpBin(endpoint, handler)`, the
  handler's prefix must match the endpoint's, otherwise the constructor throws.

## Compatibility

[psf/httpbin](https://github.com/psf/httpbin)'s own test suite runs against this
server in CI, which measures the subset above rather than merely describing it.
Each test that does not pass names the endpoint that is missing or the behavior
that differs.  See [src/test/python](src/test/python) to run the suite, or to
work through one of those differences.

## References

* [httpbin](https://httpbin.org/) - original Python implementation
* [go-httpbin](https://github.com/ahmetb/go-httpbin) - Go reimplementation

## License

Copyright (C) 2018-2023 Andrew Gaul<br />
Copyright (C) 2015-2016 Bounce Storage

Licensed under the Apache License, Version 2.0
