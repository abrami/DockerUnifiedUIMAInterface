# Letter Counter: A DUUI Logging Example

A minimal DUUI Python component that counts letter frequencies in a document. Its
purpose is to demonstrate [`DUUI Logging`](https://texttechnologylab.github.io/DUUIlogger/)
Every log level, exception/stacktrace capture, forwarding third-party
`logging`/`warnings` output is included, and also surfaces on the Java/DUUI console.

See [`letter_counter.py`](letter_counter.py) for the example code.

- **Docs / homepage:** https://texttechnologylab.github.io/DUUIlogger/
- **Start file:** [`LoggingTest.java`](../../../src/test/java/LoggingTest.java): a JUnit
  test that starts a `DUUIComposer`, connects to this component via `DUUIRemoteDriver` on
  `localhost:25591`, and runs a sample document through it so you can watch the logs print.

## Running it

Install [`uv`](https://docs.astral.sh/uv/getting-started/installation/):

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Then, from **this** directory, start the component:

```bash
uv run letter_counter.py
```

`uv run` resolves and syncs the dependencies from `pyproject.toml`/`uv.lock` on its own.
With the component running on port `25591`, run
`LoggingTest.runDUUI` from your IDE (or `mvn test -Dtest=LoggingTest`) to send it a
document and see the logs come through.
