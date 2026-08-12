# duui-logging

Logging library for **DUUI** Python tool components. It lets a tool emit structured
**info / warning / error** logs during a request and have them surfaced on the **Java**
side (printed to the Java console and collected in `composer.getEvents()`).

## How it works

Logs ride back to Java on the `/v1/process` response — **no connection back to the
composer is opened**:

1. Add the middleware, which buffers everything logged during a request and writes it
   into a `DUUI-Logs` response header (a size-capped JSON array):

   ```python
   import duui_logging
   app.add_middleware(duui_logging.DUUILoggingMiddleware)
   ```

2. Log with the prefab helpers. They also capture the exception traceback when called
   inside an `except` block:

   ```python
   from duui_logging import log_info, log_warn, log_error

   log_info("Processing document")
   log_warn("Document is empty")
   try:
       ...
   except Exception:
       log_error("Processing failed")  # exception traceback attached automatically
   ```

The Java side adds correlation (which component, which document) — the tool does not send
them.

## Wire format

Each record is `{level, message, logger, stacktrace, timestamp}`.
