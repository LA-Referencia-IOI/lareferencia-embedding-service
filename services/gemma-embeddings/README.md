# Servicio local de EmbeddingGemma 2

API de inferencia de texto en FP32 con selección automática de dispositivo. Devuelve un vector normalizado
por texto en el mismo orden que la solicitud. El parámetro `dimensions` admite
768 (valor por defecto), 512 y 256. Para vectores reducidos, el servicio conserva
las primeras componentes hasta la dimensión solicitada y vuelve a normalizar el vector con norma L2. Usar la misma
dimensión para indexar documentos y codificar consultas.

## Ejecutarlo

Desde este directorio, con Python 3.12 en un entorno virtual:

```bash
# En Linux, instala las ruedas de CPU antes de las demás dependencias.
python -m pip install torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000
```

En macOS, instala directamente `requirements.txt`. Para Docker, construye
con `docker build -t lr-gemma-embeddings .` y ejecuta con caché persistente:

```bash
docker run --rm -p 127.0.0.1:8000:8000 \
  -v lr-gemma-cache:/cache/huggingface lr-gemma-embeddings
```

Desde la raíz del repositorio también puedes usar
`docker compose --profile gemma up --build gemma-embeddings`.

El servicio busca la revisión en la caché al arrancar y la descarga si
falta. Si Hugging Face requiere aceptar las condiciones del modelo,
acéptalas y configura `HF_TOKEN` en el entorno (en Docker, pásalo con
`-e HF_TOKEN`). El arranque falla si el modelo no se puede cargar.
`GET /health/ready` responde una vez cargado y muestra la revisión activa y el dispositivo utilizado.

| Variable | Valor por defecto y uso |
|---|---|
| `GEMMA_REVISION` | `f9972ac22e696a105c482202646979e33e4b5a1e`. Commit exacto de Hugging Face. |
| `GEMMA_OFFLINE` | `false`. Con `true` solo usa la caché. |
| `GEMMA_DEVICE` | `auto` elige CUDA, después MPS y finalmente CPU. Usa `cuda`, `mps` o `cpu` para fijar el dispositivo. Si el dispositivo explícito no está disponible, el arranque falla. |
| `GEMMA_CPU_THREADS` | `4` hilos de inferencia. |
| `GEMMA_TASK` | `raw`. También acepta `retrieval-query`, `retrieval-document` y `classification`. |
| `GEMMA_API_KEY` | Opcional. Si se define, exige `Authorization: Bearer <clave>` en el endpoint de embeddings. |
| `HF_HOME` | Caché de Hugging Face. En Docker, `/cache/huggingface`. |

## Usar la GPU

En Apple Silicon, ejecuta el servicio de forma nativa con PyTorch para
macOS. La imagen Linux de Docker no detecta MPS. En NVIDIA, instala
PyTorch con CUDA o construye la imagen con el índice adecuado al driver.
Este ejemplo usa las ruedas CUDA 12.8 y requiere NVIDIA Container Toolkit:

```bash
docker build --build-arg TORCH_INDEX_URL=https://download.pytorch.org/whl/cu128 -t lr-gemma-embeddings-cuda .
docker run --rm --gpus all -e GEMMA_DEVICE=cuda -p 127.0.0.1:8000:8000 \
  -v lr-gemma-cache:/cache/huggingface lr-gemma-embeddings-cuda
```

Compose usa la imagen CPU por defecto. `auto` detecta únicamente los
dispositivos visibles para el proceso y compatibles con su PyTorch.

## Solicitud

```bash
curl -fsS http://127.0.0.1:8000/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"google/embeddinggemma-2","input":["ciencia abierta","datos públicos"],"dimensions":768,"task":"retrieval-query"}'
```

`input` puede ser un texto o una lista. La respuesta incluye `object`,
`model`, `data[].index`, `data[].embedding` y `usage`. Se admite
`encoding_format` igual a `float` (por defecto) o `base64` (float32
little-endian). Se acepta `user`. El campo opcional `task` es una extensión
del servicio y prevalece sobre `GEMMA_TASK`.

Usa `raw` cuando el cliente ya aplica el prefijo al texto. Si envías la
pregunta sin prefijo, usa `retrieval-query`. `retrieval-document` aplica
`title: none | text: `. Para documentos con título, construye
`title: {title} | text: {text}` y envíalo con `raw`.

Límites por petición: 1–16 textos, 131.072 caracteres, 8.192 tokens por
texto y 16.384 tokens en total, incluidos los prefijos. Una petición
demasiado larga recibe HTTP 400. Una petición concurrente mientras el
modelo esté ocupado recibe HTTP 429. Se recomienda un solo worker por
instancia para cargar una sola copia del modelo.
