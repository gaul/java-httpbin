#
# Copyright 2018-2023 Andrew Gaul <andrew@gaul.org>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Runs the upstream httpbin test suite against a live java-httpbin server.

Upstream drives its Flask application in process through
``httpbin.app.test_client()``.  Replacing that factory with a client that
speaks HTTP to a real server lets ``upstream/tests/test_httpbin.py`` stay
byte-identical, so the suite can be tracked as a pinned submodule instead of
a fork that has to be rebased.

Tests that java-httpbin cannot pass are marked here rather than edited out of
the upstream file.  ``XFAIL`` entries are strict, so implementing an endpoint
or fixing a divergence turns the run red until its entry is deleted.
"""

import contextlib
import json
import os
import socket
import subprocess
import time
from pathlib import Path

import pytest
import requests
from werkzeug.datastructures import Headers

import httpbin

ROOT = Path(__file__).resolve().parents[3]
STARTUP_TIMEOUT = 60.0

#: Tests java-httpbin does not intend to pass, and why.
SKIP = {
    # These drive the Python package instead of the HTTP surface, so running
    # them against java-httpbin would only re-test upstream.
    "test_bytes_endpoint_yields_bytes":
        "exercises the Python WSGI application, not the HTTP surface",
    "test_index_falls_back_to_static_page_without_flasgger":
        "exercises the Python package, not the HTTP surface",
    "test_sources_have_no_invalid_escape_sequences":
        "lints the Python sources, not the HTTP surface",

    # Reproducing these would mean reimplementing MT19937 in Java.
    "test_bytes_with_seed":
        "seeded bytes come from java.util.Random, not Python's Mersenne "
        "Twister",
    "test_stream_bytes_with_seed":
        "seeded bytes come from java.util.Random, not Python's Mersenne "
        "Twister",
}

#: Divergences from upstream that java-httpbin would accept a fix for.
#: Deleting an entry is the last step of fixing one, and a strict xfail makes
#: the run red until that happens.
XFAIL = {
    # Endpoints java-httpbin does not serve, which answer 501.
    "test_bearer_auth": "/bearer is not implemented",
    "test_bearer_auth_with_missing_token": "/bearer is not implemented",
    "test_bearer_auth_with_wrong_authorization_type":
        "/bearer is not implemented",
    "test_brotli": "/brotli is not implemented",
    "test_digest_auth": "/digest-auth is not implemented",
    "test_digest_auth_with_wrong_authorization_type":
        "/digest-auth is not implemented",
    "test_digest_auth_with_wrong_password":
        "/digest-auth is not implemented",
    "test_digest_auth_wrong_pass": "/digest-auth is not implemented",

    # Upstream answers every request with CORS headers.
    "test_set_cors_allow_headers":
        "Access-Control-Allow-Headers is not sent",
    "test_set_cors_credentials_headers_after_auth_request":
        "Access-Control-Allow-Credentials is not sent",
    "test_set_cors_headers_after_request":
        "Access-Control-Allow-Origin is not sent",
    "test_set_cors_headers_after_request_with_request_origin":
        "Access-Control-Allow-Origin is not sent",
    "test_set_cors_headers_with_options_verb":
        "the CORS preflight headers are not sent",

    # Behavior differences on endpoints java-httpbin does serve.
    "test_anything": "the JSON body does not end in a newline",
    "test_get": "the JSON body does not end in a newline",
    "test_delete_endpoint_returns_body":
        "a urlencoded body is not reported in the form field",
    "test_etag_if_match_matches_list":
        "If-Match matches one entity tag rather than a list",
    "test_etag_if_none_match_matches_list":
        "If-None-Match matches one entity tag rather than a list",
    "test_etag_if_none_match_w_prefix":
        "If-None-Match does not match a W/ weak entity tag",
    "test_response_headers_multi":
        "/response-headers sends only the first value of a repeated "
        "parameter",
    "test_response_headers_simple": "/response-headers sends no body",
    "test_x_forwarded_proto":
        "X-Forwarded-Proto does not change the reported url",

    # Upstream asserts its own limitation here: it answers 501 to a chunked
    # request, which java-httpbin accepts.  Upstream carries the assertion it
    # wants next to the one it makes.
    "test_post_chunked": "a chunked request succeeds instead of failing",
}

_base_url = None


def _free_port():
    with contextlib.closing(socket.socket()) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _jar():
    jars = list((ROOT / "target").glob(
        "httpbin-*-jar-with-dependencies.jar"))
    if not jars:
        pytest.exit("no executable jar in target/; run `mvn package` first "
                    "or point HTTPBIN_URL at a running server", returncode=1)
    # An older version may linger from before a bump; take the last built.
    return max(jars, key=lambda jar: jar.stat().st_mtime)


def _java():
    java_home = os.environ.get("JAVA_HOME")
    return str(Path(java_home, "bin", "java")) if java_home else "java"


def _await_server(url, process=None):
    deadline = time.monotonic() + STARTUP_TIMEOUT
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            pytest.exit("server exited with status %d during startup"
                        % process.returncode, returncode=1)
        try:
            requests.get(url + "/status/200", timeout=1.0)
            return
        except requests.RequestException:
            time.sleep(0.1)
    pytest.exit("server did not answer %s within %g seconds"
                % (url, STARTUP_TIMEOUT), returncode=1)


@pytest.fixture(scope="session", autouse=True)
def server():
    """Serves the endpoints under test, starting a server unless given one."""
    global _base_url
    url = os.environ.get("HTTPBIN_URL")
    if url:
        _base_url = url.rstrip("/")
        _await_server(_base_url)
        yield _base_url
        return

    _base_url = "http://127.0.0.1:%d" % _free_port()
    process = subprocess.Popen([_java(), "-jar", str(_jar()), _base_url])
    try:
        _await_server(_base_url, process)
        yield _base_url
    finally:
        process.terminate()
        with contextlib.suppress(subprocess.TimeoutExpired):
            process.wait(timeout=10)


def _body(data, headers):
    """Sends a chunked body when the caller asked for chunked encoding."""
    encoding = headers.get("Transfer-Encoding", "")
    if encoding.lower() != "chunked" or not isinstance(data, (bytes, str)):
        return data
    # requests chunks an iterable body and sets the header itself; leaving the
    # caller's header in place would send it twice.
    del headers["Transfer-Encoding"]
    return iter([data.encode("utf-8") if isinstance(data, str) else data])


class _Response:
    """Presents an HTTP response the way upstream's test client does."""

    def __init__(self, response):
        self._response = response
        self.status_code = response.status_code
        self.data = response.content
        # urllib3 keeps repeated headers apart, which get_all() needs.
        self.headers = Headers(response.raw.headers.items())
        self.content_type = self.headers.get("Content-Type")
        length = self.headers.get("Content-Length")
        self.content_length = int(length) if length is not None else None

    @property
    def json(self):
        return json.loads(self.data)

    def get_data(self, as_text=False):
        return self._response.text if as_text else self.data


class _Client:
    """Speaks HTTP where upstream's test client would call into WSGI."""

    #: Accepted and dropped: a WSGI detail with no over-the-wire equivalent.
    #: Upstream only sets REMOTE_ADDR, which a real connection supplies.
    IGNORED = frozenset(["environ_base"])

    def get(self, path, **kwargs):
        return self.open(path, method="GET", **kwargs)

    def post(self, path, **kwargs):
        return self.open(path, method="POST", **kwargs)

    def put(self, path, **kwargs):
        return self.open(path, method="PUT", **kwargs)

    def patch(self, path, **kwargs):
        return self.open(path, method="PATCH", **kwargs)

    def delete(self, path, **kwargs):
        return self.open(path, method="DELETE", **kwargs)

    def head(self, path, **kwargs):
        return self.open(path, method="HEAD", **kwargs)

    def options(self, path, **kwargs):
        return self.open(path, method="OPTIONS", **kwargs)

    def open(self, path, method="GET", headers=None, data=None,
             content_type=None, **kwargs):
        unsupported = set(kwargs) - self.IGNORED
        if unsupported:
            raise TypeError("upstream now passes %s, which this client does "
                            "not translate" % sorted(unsupported))
        headers = dict(headers or {})
        if content_type is not None:
            headers["Content-Type"] = content_type
        # _body() may drop a header, so resolve it before the request.
        body = _body(data, headers)
        return _Response(requests.request(
            method, _base_url + path, headers=headers, data=body,
            allow_redirects=False, timeout=30))


def pytest_collection_modifyitems(config, items):
    unmatched = set(SKIP) | set(XFAIL)
    for item in items:
        unmatched.discard(item.name)
        if item.name in SKIP:
            item.add_marker(pytest.mark.skip(reason=SKIP[item.name]))
        elif item.name in XFAIL:
            item.add_marker(pytest.mark.xfail(reason=XFAIL[item.name],
                                              strict=True))
    if unmatched:
        # Upstream renamed or dropped these; the entries would silently stop
        # covering anything.
        pytest.exit("no such test: %s" % ", ".join(sorted(unmatched)),
                    returncode=1)


httpbin.app.test_client = lambda *args, **kwargs: _Client()
