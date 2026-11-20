"""OpenAI-compatible local text inference for EmbeddingGemma 2."""
import base64
import logging
import os
import re
from contextlib import asynccontextmanager
from threading import BoundedSemaphore
from typing import Literal

from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, StrictInt

MODEL = "google/embeddinggemma-2"
REVISION = "f9972ac22e696a105c482202646979e33e4b5a1e"
DIMENSIONS = 768
PREFIXES = {
    "raw": "",
    "retrieval-query": "task: search result | query: ",
    "retrieval-document": "title: none | text: ",
    "classification": "task: classification | query: ",
}
Task = Literal["raw", "retrieval-query", "retrieval-document", "classification"]
logger = logging.getLogger(__name__)


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str
    input: str | list[str]
    task: Task | None = None
    dimensions: StrictInt | None = None
    encoding_format: Literal["float", "base64"] = "float"
    user: str | None = None


def model_snapshot(revision, offline):
    from huggingface_hub import snapshot_download
    from huggingface_hub.errors import LocalEntryNotFoundError

    try:
        return snapshot_download(MODEL, revision=revision, local_files_only=True)
    except LocalEntryNotFoundError:
        if offline:
            raise
        logger.warning("Downloading %s revision %s", MODEL, revision)
        return snapshot_download(MODEL, revision=revision)


def select_device(torch, requested):
    if requested in {"auto", "cuda"} and torch.cuda.is_available():
        return "cuda"
    mps = getattr(torch.backends, "mps", None)
    if requested in {"auto", "mps"} and mps is not None and mps.is_available():
        return "mps"
    if requested in {"auto", "cpu"}:
        return "cpu"
    raise RuntimeError(f"Requested device is unavailable: {requested}")


class Gemma:
    def __init__(self, revision, offline, threads, device="auto"):
        import numpy as np
        import torch
        from sentence_transformers import SentenceTransformer

        torch.set_num_threads(threads)
        self.np = np
        self.device = select_device(torch, device)
        logger.info("Using EmbeddingGemma 2 on %s (float32)", self.device)
        self.model = SentenceTransformer(
            model_snapshot(revision, offline), device=self.device, local_files_only=True,
            model_kwargs={"torch_dtype": torch.float32},
            config_kwargs={"vision_config": None, "audio_config": None},
        )
        self.model.max_seq_length = 8192
        self.model.eval()
        if self.model.get_embedding_dimension() != DIMENSIONS:
            raise RuntimeError("Unexpected model dimensions")
        for task, prompt in (("retrieval-query", "Retrieval-query"),
                             ("retrieval-document", "Retrieval-document"),
                             ("classification", "Classification")):
            if self.model.prompts.get(prompt) != PREFIXES[task]:
                raise RuntimeError(f"Unexpected model prompt: {prompt}")

    def embed(self, texts):
        ids = self.model.tokenizer(texts, truncation=False, add_special_tokens=True, verbose=False)["input_ids"]
        counts = [len(item) for item in ids]
        if any(count > 8192 for count in counts) or sum(counts) > 16384:
            raise ValueError("Token limit exceeded (8192 per text, 16384 per request)")
        vectors = self.np.asarray(self.model.encode(
            texts, prompt="", batch_size=1, normalize_embeddings=True,
            convert_to_numpy=True, show_progress_bar=False,
        ), dtype=self.np.float32)
        if vectors.shape != (len(texts), DIMENSIONS) or not self.np.isfinite(vectors).all():
            raise RuntimeError("Invalid model output")
        norms = self.np.linalg.norm(vectors, axis=1, keepdims=True)
        if not self.np.isfinite(norms).all() or (norms <= 0).any():
            raise RuntimeError("Invalid vector norms")
        return vectors / norms, sum(counts)


def error(status, message, code, retry=False):
    return JSONResponse(
        status_code=status, headers={"Retry-After": "1"} if retry else None,
        content={"error": {"message": message, "type": "invalid_request_error" if status < 500 else "server_error",
                           "param": None, "code": code}},
    )


def create_app(loader=Gemma):
    @asynccontextmanager
    async def lifespan(api):
        task = os.getenv("GEMMA_TASK", "raw")
        revision = os.getenv("GEMMA_REVISION", REVISION)
        threads = int(os.getenv("GEMMA_CPU_THREADS", "4"))
        offline = os.getenv("GEMMA_OFFLINE", "false").lower()
        device = os.getenv("GEMMA_DEVICE", "auto").lower()
        if device not in {"auto", "cpu", "cuda", "mps"}:
            raise ValueError("GEMMA_DEVICE must be auto, cpu, cuda or mps")
        if task not in PREFIXES or not re.fullmatch(r"[a-f0-9]{40}", revision) or threads < 1 or offline not in {"true", "false"}:
            raise ValueError("Invalid GEMMA_TASK, GEMMA_REVISION, GEMMA_CPU_THREADS or GEMMA_OFFLINE")
        api.state.encoder = loader(revision, offline == "true", threads, device)
        api.state.task = task
        api.state.revision = revision
        api.state.token = os.getenv("GEMMA_API_KEY")
        api.state.slot = BoundedSemaphore(1)
        yield

    api = FastAPI(lifespan=lifespan)

    @api.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        return error(400, "Invalid embedding request", "invalid_request")

    @api.get("/health/ready")
    def ready(request: Request):
        return {"status": "ready", "model": MODEL, "revision": request.app.state.revision,
                "device": request.app.state.encoder.device}

    @api.post("/v1/embeddings")
    def embeddings(body: EmbeddingRequest, request: Request, authorization: str | None = Header(default=None)):
        import secrets

        state = request.app.state
        if state.token and not secrets.compare_digest((authorization or "").encode(), f"Bearer {state.token}".encode()):
            return error(401, "Invalid API key", "invalid_api_key")
        if body.model != MODEL:
            return error(404, "Unknown model", "model_not_found")
        if body.dimensions not in (None, DIMENSIONS):
            return error(400, "Only 768 dimensions are supported", "invalid_dimensions")
        inputs = [body.input] if isinstance(body.input, str) else body.input
        if not 1 <= len(inputs) <= 16 or any(not item.strip() for item in inputs) or sum(map(len, inputs)) > 131072:
            return error(400, "Expected 1–16 nonblank texts and at most 131072 characters", "invalid_input")
        prefix = PREFIXES[body.task or state.task]
        if not state.slot.acquire(blocking=False):
            return error(429, "Inference capacity exhausted", "rate_limit_exceeded", retry=True)
        try:
            vectors, tokens = state.encoder.embed([prefix + item for item in inputs])
        except ValueError:
            return error(400, "Text exceeds model input limits", "invalid_input")
        except Exception:
            logger.exception("Embedding inference failed")
            return error(500, "Embedding inference failed", "inference_failed")
        finally:
            state.slot.release()
        return {
            "object": "list",
            "data": [{"object": "embedding", "index": i, "embedding": (
                vector.tolist() if body.encoding_format == "float"
                else base64.b64encode(vector.astype("<f4", copy=False).tobytes()).decode("ascii")
            )} for i, vector in enumerate(vectors)],
            "model": MODEL,
            "usage": {"prompt_tokens": tokens, "total_tokens": tokens},
        }

    return api


app = create_app()
