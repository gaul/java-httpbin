# Java httpbin

A Java-based HTTP server that lets you test your HTTP client, retry logic,
streaming behavior, timeouts etc. with the endpoints of
[httpbin.org](https://httpbin.org/) locally.

This way, you can write tests without relying on an external dependency like
httpbin.org.

## Endpoints

Java httpbin supports a subset of httpbin endpoints:

| Endpoint                               | Description                                                          |
| -------------------------------------- | -------------------------------------------------------------------- |
| `/get`                                 | Returns GET data.                                                    |
| `/post`                                | Returns POST data.                                                   |
| `/patch`                               | Returns PATCH data.                                                  |
| `/put`                                 | Returns PUT data.                                                    |
| `/status/:code`                        | Returns given HTTP Status code or random if more than one are given. |

## References

* [httpbin](https://httpbin.org/) - original Python implementation
* [go-httpbin](https://github.com/ahmetb/go-httpbin) - Go reimplementation

## License

Copyright (C) 2018 Andrew Gaul<br />
Copyright (C) 2015-2016 Bounce Storage

Licensed under the Apache License, Version 2.0
