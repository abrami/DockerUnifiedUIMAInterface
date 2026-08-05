"""Per-request log buffer.

With the piggyback transport there is no connection back to Java. Instead, each log
record produced while handling a ``/v1/process`` request is appended to a request-scoped
buffer; the middleware then serialises that buffer into a response header that the Java
driver reads off the HTTP response. The buffer lives in a :class:`~contextvars.ContextVar`
so concurrent requests never mix, and because a ``ContextVar`` copied into a threadpool
worker shares the *same* list object, records appended from a sync endpoint are still
visible to the middleware.
"""

from __future__ import annotations

from contextvars import ContextVar
from typing import List, Optional

from .records import LogRecord

# HTTP headers exchanged with the Java side (mirrors IDUUIInstantiatedPipelineComponent).
HEADER_LOG_COLLECT = "duui-log-collect"  # request: Java asks the tool to return its logs
HEADER_LOGS = "duui-logs"                # response: the tool's logs as a JSON array

# None means "not collecting" (no active request or Java didn't ask for logs).
_buffer: ContextVar[Optional[List[LogRecord]]] = ContextVar("duui_log_buffer", default=None)


def start_buffer():
    """Begin collecting logs for the current request. Returns a reset token."""
    return _buffer.set([])


def reset_buffer(token) -> None:
    """Stop collecting, restoring the previous buffer state."""
    try:
        _buffer.reset(token)
    except (LookupError, ValueError):
        pass


def get_buffer() -> Optional[List[LogRecord]]:
    """Return the current request's buffer, or ``None`` if not collecting."""
    return _buffer.get()


def collect(record: LogRecord) -> None:
    """Append a record to the current request's buffer, if one is active."""
    buffer = _buffer.get()
    if buffer is not None:
        buffer.append(record)
