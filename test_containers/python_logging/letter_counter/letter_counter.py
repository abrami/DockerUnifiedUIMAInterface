"""
Counts how often each letter appears in total accross multiple documents
"""

from fastapi import FastAPI, Response
from fastapi.encoders import jsonable_encoder
from fastapi.responses import PlainTextResponse
from starlette.responses import JSONResponse
from pydantic import BaseModel
from typing import Optional
import threading
import uvicorn
import os
import ray
import signal
import time

# DUUI logging: surface info/warning/error on the Java composer (returned on the response).
# Falls back to plain prints if the library isn't installed, so the tool still runs.
try:
    import duui_logging
except ImportError:  # pragma: no cover - optional dependency
    duui_logging = None


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
    title="DUUI Ray Letter Counter",
    description="Counts letter frequencies using Ray parallel workers",
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

# -- Logging -------------
# Two steps to make logs show up on the Java side:
#   1) add DUUILoggingMiddleware (it collects logs during a request and returns them to DUUI
#      on the /v1/process response — no connection back to DUUI is opened);
#   2) log with the prefab helpers log_info / log_warn / log_error / log_critical, which also
#      capture the exception traceback when called inside an `except` block.
if duui_logging is not None:
    app.add_middleware(duui_logging.DUUILoggingMiddleware)
    from duui_logging import log_info, log_warn, log_error
else:  # standalone fallback: keep the same call sites working without the library
    def log_info(message, **kwargs):
        print(f"[INFO] {message}")

    def log_warn(message, **kwargs):
        print(f"[WARN] {message}")

    def log_error(message, **kwargs):
        print(f"[ERROR] {message}")

# -- Communication layer (Lua script) ------------

_lua_path =  "communication.lua"
with open(_lua_path, "rb") as f:
    _communication_script = f.read().decode("utf-8")


# -- Ray worker function ------------------------

def _count_letters_chunk(chunk: str) -> dict:
    """
    Count letter occurrences in a single text chunk.
    Runs on a Ray worker — one remote call per chunk.
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
        annotator_name="DUUI Ray Letter Counter",
        version="1.0.0",
        implementation_lang="Python",
    )


@app.post("/v1/process")
async def process(request: DUUIRequest) -> DUUIResponse:
    """
    Count letter frequencies in one document and return the sorted counts.
    """
    log_info(f"Processing document ({len(request.text)} chars)")

    if not request.text.strip():
        log_warn("Document is empty — it will contribute no letter counts")

    try:
        total: dict[str, int] = _count_letters_chunk(request.text)
    except Exception:
        log_error("Letter counting failed")   # exception traceback attached automatically
        raise

    total = dict(sorted(total.items()))

    log_info(f"Done: {len(total)} unique letters", withTimeStamp=True, withStacktrace=True, withStackTraceDepth=20)
    return DUUIResponse(counts=total)

if __name__ == "__main__":
    uvicorn.run("letter_counter:app", host="0.0.0.0", port=25591, workers=1)
