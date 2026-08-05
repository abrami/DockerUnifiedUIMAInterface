"""Prefab logging helpers for DUUI Python components.

Each ``log_*`` function builds a structured record — level, message, optional timestamp
and stacktrace — appends it to the current request's log buffer (so the middleware can
return it to Java on the response), and echoes it to stderr so it stays visible in the
tool's own container output. Every helper also returns the
:class:`~duui_logging.records.LogRecord` it emitted.

Correlation (which component / which document) is added by the Java side, which already
knows both — so the tool does not need to send them.

Stacktraces come in two flavours:

* ``withException`` (default on for ``error`` / ``critical``): if the call happens while an
  exception is being handled (inside an ``except`` block), the real exception traceback is
  attached — the most useful thing when something breaks.
* ``withStacktrace``: attach the current *call stack* (via :func:`where_am_i`) showing where
  the log call was made, even when there is no exception.

If both are requested, an active exception traceback wins.
"""

from __future__ import annotations

import sys
import time
import traceback
from enum import Enum
from typing import Optional

from . import context
from .records import LogRecord


class ErrorLevel(str, Enum):
    """Log severity levels (names match what the Java receiver understands)."""

    TRACE = "TRACE"
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARN = "WARN"
    ERROR = "ERROR"
    CRITICAL = "CRITICAL"


def where_am_i(depth: int = 1, _skip: int = 0) -> str:
    """Return the current call stack (innermost first), excluding this frame.

    :param depth: how many of the most recent frames to include; ``<= 0`` means all.
    :param _skip: internal — extra caller frames to drop (used when called via ``_emit``).
    """
    frames = traceback.extract_stack()[: -(1 + _skip)]  # drop where_am_i (+ internal wrappers)
    if depth and depth > 0:
        frames = frames[-depth:]
    return "\n".join(f"{f.name} ({f.filename}:{f.lineno})" for f in reversed(frames))


def current_exception_trace() -> Optional[str]:
    """Return the traceback of the exception currently being handled, or ``None``."""
    exc = sys.exc_info()[1]
    if exc is None:
        return None
    return "".join(
        traceback.format_exception(type(exc), exc, exc.__traceback__)
    ).rstrip()


def _emit(
    level: ErrorLevel,
    message: str,
    *,
    withTimeStamp: bool,
    withStacktrace: bool,
    withStackTraceDepth: int,
    withException: bool,
    logger: str = "duui",
) -> LogRecord:
    """Build, echo and buffer a single record. Shared by every ``log_*`` helper."""
    stacktrace: Optional[str] = None
    if withException:
        stacktrace = current_exception_trace()
    if stacktrace is None and withStacktrace:
        stacktrace = where_am_i(withStackTraceDepth, _skip=1)  # drop the _emit frame

    record = LogRecord(
        level=level.value,
        message=message,
        logger=logger,
        stacktrace=stacktrace,
        timestamp=int(time.time() * 1000) if withTimeStamp else None,
    )

    # Local visibility in the container's own stdout/stderr.
    line = f"[{record.level}] {logger}: {message}"
    if stacktrace:
        line += "\n" + stacktrace
    print(line, file=sys.stderr, flush=True)

    # Buffered for return to Java on the response (no-op outside a collecting request).
    context.collect(record)

    return record


def log_trace(message: str = "", withTimeStamp: bool = True, withStacktrace: bool = True,
              withStackTraceDepth: int = 200, withException: bool = False, **kwargs) -> LogRecord:
    """Log a trace message (verbose; full call stack by default)."""
    return _emit(ErrorLevel.TRACE, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)


def log_debug(message: str, withTimeStamp: bool = True, withStacktrace: bool = True,
              withStackTraceDepth: int = 30, withException: bool = False, **kwargs) -> LogRecord:
    """Log a debug message."""
    return _emit(ErrorLevel.DEBUG, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)


def log_info(message: str, withTimeStamp: bool = False, withStacktrace: bool = False,
             withStackTraceDepth: int = 1, withException: bool = False, **kwargs) -> LogRecord:
    """Log an info message (lightweight; no stacktrace by default)."""
    return _emit(ErrorLevel.INFO, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)


def log_warn(message: str, withTimeStamp: bool = True, withStacktrace: bool = False,
             withStackTraceDepth: int = 1, withException: bool = False, **kwargs) -> LogRecord:
    """Log a warning message."""
    return _emit(ErrorLevel.WARN, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)


# Alias for callers that prefer the full word.
log_warning = log_warn


def log_error(message: str, withTimeStamp: bool = True, withStacktrace: bool = False,
              withStackTraceDepth: int = 10, withException: bool = True, **kwargs) -> LogRecord:
    """Log an error. Inside an ``except`` block the exception traceback is attached."""
    return _emit(ErrorLevel.ERROR, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)


def log_critical(message: str, withTimeStamp: bool = True, withStacktrace: bool = True,
                 withStackTraceDepth: int = 70, withException: bool = True, **kwargs) -> LogRecord:
    """Log a critical error. Attaches the exception traceback if one is active."""
    return _emit(ErrorLevel.CRITICAL, message, withTimeStamp=withTimeStamp,
                 withStacktrace=withStacktrace, withStackTraceDepth=withStackTraceDepth,
                 withException=withException, **kwargs)
