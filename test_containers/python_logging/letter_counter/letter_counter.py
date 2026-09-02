"""
Counts how often each letter appears in total accross multiple documents
"""

import logging
import warnings

import duui_logging
from duui_logging import log_debug, log_info, log_trace, log_warn, log_error, log_critical
from fastapi import FastAPI, Response
from fastapi.encoders import jsonable_encoder
from fastapi.responses import PlainTextResponse
from starlette.responses import JSONResponse
from pydantic import BaseModel
from typing import Optional
import threading
import uvicorn
import os
import signal
import time

# -- Request / Response -------------

class DUUIRequest(BaseModel):
    text: str

class DUUIResponse(BaseModel):
    counts: dict


class DUUIDocumentation(BaseModel):
    annotator_name: str
    version: str
    implementation_lang: str


# -- FastAPI app ---------------

app = FastAPI(
    docs_url="/api",
    redoc_url=None,
    title="DUUI Letter Counter Logging Example",
    description="Counts letter frequencies",
    version="1.0.0",
    terms_of_service="https://www.texttechnologylab.org/legal_notice/",
    contact={
        "name": "Daniel Bundan",
        "url": "https://texttechnologylab.org",
        "email": "bundan@em.uni-frankfurt.de",
    },
    license_info={
        "name": "AGPL",
        "url": "http://www.gnu.org/licenses/agpl-3.0.en.html",
    },
)

# -- LoggingTest -------------
# Two steps to make logs show up on the Java side:
#   1) add DUUILoggingMiddleware (it collects logs during a request and returns them to DUUI
#      on the /v1/process response — no connection back to DUUI is opened);
#   2) log with the prefab helpers log_info / log_warn / log_error / log_critical, which also
#      capture the exception traceback when called inside an `except` block.
duui_logging.add_logging(app)

# Also forward logs emitted by third-party libraries through the stdlib `logging` module
# (and warnings via `warnings.warn`) to the Java side. Call once, at startup.
duui_logging.install(level=logging.INFO)

# Stand-in for "some library" that logs the classic out-of-date notice. Just for testing :)
_lib_logger = logging.getLogger("some_library")


# -- Communication layer (Lua script) ------------

_lua_path =  "communication.lua"
with open(_lua_path, "rb") as f:
    _communication_script = f.read().decode("utf-8")


# -- Count function ------------------------

def _count_letters_chunk(chunk: str) -> dict:
    """
    Count letter occurrences in a text.
    """
    counts: dict[str, int] = {}
    for char in chunk.lower():
        if char.isalpha():
            counts[char] = counts.get(char, 0) + 1
    return counts


# -- Endpoints ------------------------

@app.get("/v1/communication_layer", response_class=PlainTextResponse)
def get_communication_layer() -> str:
    return _communication_script


@app.get("/v1/typesystem")
def get_typesystem() -> Response:
    # No custom UIMA types needed — results are stored as sofa data.
    empty_ts = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<typeSystemDescription xmlns="http://uima.apache.org/resourceSpecifier">'
        "</typeSystemDescription>"
    )
    return Response(content=empty_ts.encode("utf-8"), media_type="application/xml")


@app.get("/v1/details/input_output")
def get_input_output() -> JSONResponse:
    return JSONResponse(content=jsonable_encoder({
        "inputs":  [],
        "outputs": [],
    }))


@app.get("/v1/documentation")
def get_documentation() -> DUUIDocumentation:
    return DUUIDocumentation(
        annotator_name="DUUI Letter Counter",
        version="1.0.0",
        implementation_lang="Python",
    )


@app.post("/v1/process")
async def process(request: DUUIRequest) -> DUUIResponse:
    """
    Count letter frequencies in one document and return the sorted counts.
    """

    # All the logs. log_error and log_critical should be used inside an except block with "withException" se to true
    log_trace("Trace Logged", withTimeStamp=0, withStacktrace=0, withException=False, logger="my-test-logger")
    log_info("Info Logged", withTimeStamp=0, withStacktrace=0, withException=False, logger="my-test-logger")
    log_debug("Debug Logged", withTimeStamp=0, withStacktrace=0, withException=False, logger="my-test-logger")
    log_warn("Warn Logged", withTimeStamp=0, withStacktrace=0, withException=False, logger="my-test-logger")
    log_error("Error Logged", withTimeStamp=0, withStacktrace=5, withException=False, logger="my-test-logger")
    log_critical("Critical Logged", withTimeStamp=0, withStacktrace=0, withException=False, logger="my-test-logger")

    # Simulates third party libraries
    _lib_logger.info("some_library initialised")
    _lib_logger.warning("some_library 1.2.0 is out of date; latest is 1.5.0")
    _lib_logger.exception("some_library failed to load config file")

    # A DeprecationWarning raised via warnings.warn is captured too (captureWarnings=True).
    warnings.warn("some_library.old_api() is deprecated", DeprecationWarning)

    # Actual example with realistic logging:
    log_info("Now logging useful stuff:")

    if not request.text.strip():
        log_warn("Document is empty. it will contribute no letter counts")
    else:
        log_info(f"Processing document ({len(request.text)} chars)")

    try:
        total: dict[str, int] = _count_letters_chunk(request.text)
    except Exception:
        log_error("Letter counting failed")   # exception traceback attached automatically
        raise

    total = dict(sorted(total.items()))

    log_info(f"Done: {len(total)} unique letters")
    return DUUIResponse(counts=total)

if __name__ == "__main__":
    uvicorn.run("letter_counter:app", host="0.0.0.0", port=25591, workers=1)
