# Local EmbeddingGemma 2 service

The FP32 text inference API selects a device automatically and returns one normalized vector per text in request order. The `dimensions` parameter accepts 768 (default), 512 and 256. For reduced vectors, the service keeps the first components up to the requested dimension and applies L2 normalization again. Use the same dimension to index documents and encode queries.

## Run the service

From this directory, use Python 3.12 in a virtual environment.

```bash
# On Linux, install the CPU wheels before the other dependencies.
python -m pip install torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000
```

On macOS, install `requirements.txt` directly. For Docker, build with `docker build -t lr-gemma-embeddings .` and run with a persistent cache.

```bash
docker run --rm -p 127.0.0.1:8000:8000 \
  -v lr-gemma-cache:/cache/huggingface lr-gemma-embeddings
```

From the repository root you can also run `docker compose --profile gemma up --build gemma-embeddings`.

At startup the service loads the revision from the cache or downloads it. If Hugging Face requires you to accept the model terms, accept them and set `HF_TOKEN` in the environment. In Docker, pass it with `-e HF_TOKEN`. Startup fails if the model cannot load. After loading, `GET /health/ready` returns the active revision and device.

| Variable | Default and behavior |
|---|---|
| `GEMMA_REVISION` | `f9972ac22e696a105c482202646979e33e4b5a1e`. Exact Hugging Face commit. |
| `GEMMA_OFFLINE` | `false`. Set to `true` to use only the cache. |
| `GEMMA_DEVICE` | `auto` tries CUDA, MPS and CPU in that order. Set `cuda`, `mps` or `cpu` to select a device explicitly. Startup fails if that device is unavailable. |
| `GEMMA_CPU_THREADS` | `4` inference threads. |
| `GEMMA_TASK` | `raw`. Also accepts `retrieval-query`, `retrieval-document` and `classification`. |
| `GEMMA_API_KEY` | Optional. When set, the embeddings endpoint requires `Authorization: Bearer <key>`. |
| `HF_HOME` | Hugging Face cache. In Docker, `/cache/huggingface`. |

## Use a GPU

On Apple Silicon, run natively with PyTorch for macOS. The Linux Docker image cannot detect MPS. On NVIDIA, install CUDA-enabled PyTorch or build the image with the package index that matches the driver. This example uses CUDA 12.8 wheels and requires NVIDIA Container Toolkit.

```bash
docker build --build-arg TORCH_INDEX_URL=https://download.pytorch.org/whl/cu128 -t lr-gemma-embeddings-cuda .
docker run --rm --gpus all -e GEMMA_DEVICE=cuda -p 127.0.0.1:8000:8000 \
  -v lr-gemma-cache:/cache/huggingface lr-gemma-embeddings-cuda
```

Compose uses the CPU image by default. `auto` detects the devices visible to the process and supported by its PyTorch build.

## Send a request

```bash
curl -fsS http://127.0.0.1:8000/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"google/embeddinggemma-2","input":["open science","public data"],"dimensions":768,"task":"retrieval-query"}'
```

`input` accepts a string or a list of strings. The response includes `object`, `model`, `data[].index`, `data[].embedding` and `usage`. Supported `encoding_format` values are `float` (default) and `base64` (little-endian float32). The endpoint accepts `user`. The optional `task` extension takes precedence over `GEMMA_TASK`.

Use `raw` when the client supplies the prefix. For an unprefixed query, use `retrieval-query`. `retrieval-document` adds `title: none | text: `. For titled documents, build `title: {title} | text: {text}` and send it with `raw`.

A request can contain 1–16 texts, up to 131,072 characters, 8,192 tokens per text and 16,384 tokens in total, including prefixes. Oversized requests receive HTTP 400. Concurrent requests arriving while the model is busy receive HTTP 429. Use one worker per instance to load one copy of the model.
