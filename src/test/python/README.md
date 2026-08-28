# Upstream httpbin suite

Runs [psf/httpbin](https://github.com/psf/httpbin)'s own tests against a
running java-httpbin, which measures how far the two agree rather than
asserting what java-httpbin already does.

Upstream drives its Flask application in process through
`httpbin.app.test_client()`.  `conftest.py` replaces that factory with a client
that speaks HTTP to a real server, so `upstream/` stays an unmodified submodule
pinned to a release and updating it is a fast-forward rather than a rebase.

## Running

Build the executable jar first; the suite starts and stops a server itself.

```
mvn package
cd src/test/python
pip install ./upstream -r requirements.txt
pytest
```

Set `HTTPBIN_URL` to test a server that is already running:

```
HTTPBIN_URL=http://127.0.0.1:8080 pytest
```

## Reading the result

A green run ends with a line like:

```
42 passed, 5 skipped, 23 xfailed
```

Every test that does not pass is listed with a reason, from the two tables at
the top of `conftest.py`:

- `SKIP` — tests java-httpbin does not intend to pass, either because they
  exercise the Python package rather than its HTTP surface, or because they
  assert bytes from Python's random number generator.
- `XFAIL` — endpoints java-httpbin does not serve, and behavior that differs
  from upstream.  Together they are the difference between the two
  implementations, named one line at a time.

Fixing a divergence means deleting its `XFAIL` entry.  The entries are strict,
so a fix that leaves one behind turns the run red, as does an entry that no
longer names a test after the submodule moves.

## Updating the pin

```
git -C upstream fetch --tags
git -C upstream checkout <tag>
pip install ./upstream
pytest
```

A test that upstream added and java-httpbin fails needs a new `XFAIL` entry; one
that upstream renamed or removed makes the run stop with `no such test`.
