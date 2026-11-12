# Document-to-vector workflows

This document contains two complete API v2 workflows. Each step is numbered and lists its prerequisites and expected result:

1. [Create documents through the API](#workflow-1--documents-created-through-the-api).
2. [Import documents from JSON or JSONL](#workflow-2--documents-imported-from-json-or-jsonl).

The workflows create separate collections and can reuse the same immutable embedding profile. Commands are examples; run them only against an environment you are authorized to use. The documents, IDs, vectors and responses shown are illustrative.

## Common prerequisites

Before starting either workflow:

- The application is running at `http://localhost:8989` with PostgreSQL and pgvector >= 0.8.0 available. The API process has `DVP_ENABLED=true` and role `API` or `ALL`.
- A worker-capable process has `DVP_ENABLED=true`, `DVP_WORKERS_ENABLED=true`, and role `WORKER` or `ALL`. A 202 response only queues work; wait for the job before using its result.
- For generation and text search, a configured HTTP provider is reachable and authorized to receive the texts. The examples use the existing provider reference `ollama-gpu-ibict`, model `bge-m3`, and 1024 dimensions; verify that your provider actually serves this model and dimension. Adjust the reference, model and declared revision to match your environment. `provider_ref` is a configured name, not a URL.
- For workflow 2, `DVP_IMPORT_ENABLED=true` is set on both the API and worker. Their import directory is shared and durable and supports atomic rename, directory fsync and cross-process locks. The supplied Compose volume is local to its Docker host.
- Replace every `<...>` placeholder with the UUID or value returned by your server. Example JSON responses below show relevant fields; real responses can include additional fields.
- `Idempotency-Key` is mandatory for collection, profile, indexation, import, generation and maintenance creation. Reusing a key with the same request replays the original response; use a **new key** for a new operation or a later generation run. Choose unused collection/indexation slugs and keys when repeating these examples.

Set the base URL in your shell:

```bash
BASE=http://localhost:8989
```

**Profile choice:** both workflows require a profile before indexation creation. You may use an existing compatible HTTP profile and set `P` to its UUID. Otherwise create the profile in the relevant workflow. Profiles are immutable and unique by slug or fingerprint; if creation returns `profile_exists`, inspect the existing profile and reuse it only if its configuration is appropriate. Do not change model weights or revision in place.

---

# Workflow 1 — Documents created through the API

## Step 1. Create a collection

**Prerequisites:** the common prerequisites; choose an unused slug and idempotency key.

```bash
curl -i -X POST "$BASE/api/v2/collections" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-api-collection-001' \
  -d '{"slug":"workflow-api","name":"API workflow collection"}'
```

**Expected:** 201 Created with a collection `id` and a `Location` header. Save the returned UUID:

```bash
C='<COLLECTION_UUID_FROM_STEP_1>'
```

## Step 2. Add a document and its texts

**Prerequisites:** collection `C` exists. `PATCH` creates or partially updates a document by its exact, collection-scoped `external_id`.

```bash
curl -i -X PATCH "$BASE/api/v2/collections/$C/documents/by-external-id?external_id=example%3Aapi-001" \
  -H 'Content-Type: application/json' \
  -d '{"metadata":{"title":"Open research","year":2024},"texts":[{"text_id":"title-en","kind":"title","language":"en","content":"Open research"},{"text_id":"abstract-en","kind":"abstract","language":"en","content":"Open access makes research easier to discover and reuse."}]}'
```

**Expected:** 201 for a new document or 200 for an update, with its `id` and an `ETag`. Save the document UUID:

```bash
D='<DOCUMENT_UUID_FROM_STEP_2>'
```

Repeat this step with other external IDs to add more documents. Ingestion does **not** generate vectors. PATCH preserves omitted text keys; when supplied, `metadata` replaces the entire metadata object. For complete replacement, use PUT with both `metadata` and `texts`; omitted texts are removed.

## Step 3. Create or select an embedding profile

**Prerequisites:** the provider reference, model, revision and dimensions have been verified. If a compatible HTTP profile already exists, set `P` to its UUID and skip the POST.

```bash
curl -i -X POST "$BASE/api/v2/embedding-profiles" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-api-profile-001' \
  -d '{"slug":"workflow-bge-m3-v1","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"http","provider_ref":"ollama-gpu-ibict","preparation":{"version":"identity-v1"}}'
```

**Expected:** 201 with a profile `id` and `Location`. If the same slug or fingerprint already exists, the API returns 409; inspect the existing profile before deciding whether to reuse it.

```bash
P='<HTTP_PROFILE_UUID>'
```

An `import_only` profile cannot run a generation job or text search. Generation and search share the profile's model space but use its document and query preparation respectively.

## Step 4. Create and provision an indexation

**Prerequisites:** collection `C` and compatible profile `P` exist; an enabled worker can process the provisioning job.

```bash
curl -i -X POST "$BASE/api/v2/collections/$C/indexations" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-api-index-001' \
  -d "{\"slug\":\"abstracts\",\"profile_id\":\"$P\",\"selection\":{\"kinds\":[\"abstract\"],\"languages\":[]},\"chunking\":{\"mode\":\"none\"},\"metric\":\"cosine\",\"ann_strategy\":\"hnsw_vector\"}"
```

**Expected:** 202 with an indexation `id`, a provisioning `job_id`, and a `Location` header pointing to the **indexation**. Save both IDs:

```bash
I='<INDEXATION_UUID_FROM_STEP_4>'
J='<PROVISIONING_JOB_UUID_FROM_STEP_4>'
```

Poll the job and inspect the indexation:

```bash
curl "$BASE/api/v2/jobs/$J"
curl "$BASE/api/v2/collections/$C/indexations/$I"
```

Continue only after the job succeeds and the indexation has `state: "ready"`. The selection `kinds:["abstract"]` selects only abstracts; `languages:[]` allows all languages. With `chunking.mode=none`, each selected text forms one vector unit. The title from step 2 is not selected by this example. An empty `kinds` list would select all text kinds, including titles.

## Step 5. Generate vectors

**Prerequisites:** document `D` exists in collection `C`, indexation `I` is ready, its profile is HTTP-capable, and the worker can reach the provider.

```bash
curl -i -X POST "$BASE/api/v2/collections/$C/indexations/$I/generation-jobs" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-api-generation-001' \
  -d "{\"document_ids\":[\"$D\"]}"
```

**Expected:** 202 with a generation job ID and a job `Location`:

```bash
J='<GENERATION_JOB_UUID_FROM_STEP_5>'
curl "$BASE/api/v2/jobs/$J"
curl "$BASE/api/v2/jobs/$J/errors"
```

Wait for `status: "succeeded"`. A newly prepared and generated abstract can produce counters such as `units_prepared: 1` and `vectors_generated: 1`; existing units or reusable vectors can change the counters. Inspect `completed_with_errors`, `failed`, `global_error_code`, and `/errors` when necessary.

To process all documents captured in the collection, submit a new job with `{"document_ids":[]}` and a **new** idempotency key. A collection-wide job captures an enumeration limit; documents added later need another job. A separate preparation job is optional because generation prepares missing units.

## Step 6. Retrieve vectors for a document or collection

**Prerequisites:** the generation job has succeeded for the document and indexation. Retrieval only reads existing vectors; it never starts generation.

For this document and indexation:

```bash
curl "$BASE/api/v2/collections/$C/indexations/$I/documents/$D/vectors?include=texts,metadata"
```

For the document across all ready indexations in the collection:

```bash
curl "$BASE/api/v2/collections/$C/documents/$D/vectors"
```

**Expected:** 200 with `document`, `indexations`, `items`, and `next_cursor`. Each vector appears in `items[].embedding`; this profile returns 1024 values. Coverage should show `complete` when every unit of every selected text has a vector. An existing document without vectors returns 200 with empty `items`; all-indexation retrieval also reports `unavailable_indexation_ids`.

There is no single endpoint that returns vectors for every document in a collection. To inspect the collection, list its documents and request vectors for each document, following `next_cursor` for **both** lists. A complete pagination example is in [Retrieving vectors for an entire collection](#retrieving-vectors-for-an-entire-collection).

## Step 7. Search

**Prerequisites:** indexation `I` is ready and contains current vectors. Text search additionally requires its HTTP provider. The following uses explicit exact mode, so it does not require an ANN index:

```bash
curl -X POST "$BASE/api/v2/collections/$C/indexations/$I/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"text":"How can research be reused?"},"mode":"exact","top_n":10,"result_unit":"document","include":["texts","metadata"]}'
```

**Expected:** 200 with ranked `results`, distances, `returned`, and `recall_guaranteed: true`. `shortfall_reason: "exhausted"` means fewer than `top_n` eligible results exist. Search responses do not return embedding arrays. For approximate search, follow the optional ANN procedure below.

---

# Workflow 2 — Documents imported from JSON or JSONL

## Step 1. Create a collection

**Prerequisites:** common prerequisites, including enabled imports and shared durable storage; choose an unused slug and idempotency key.

```bash
curl -i -X POST "$BASE/api/v2/collections" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-import-collection-001' \
  -d '{"slug":"workflow-import","name":"Import workflow collection"}'
```

**Expected:** 201 with a collection UUID and `Location`:

```bash
C='<COLLECTION_UUID_FROM_STEP_1>'
```

## Step 2. Import JSONL or JSON

**Prerequisites:** collection `C` exists; import is enabled on API and worker; the shared import directory is configured. Choose **one** of the following formats. Each example uses a new temporary directory and writes only synthetic content.

### Option A: JSONL

Each nonempty line is an independent record containing `schema_version: "1"`:

```bash
WORK_DIR=$(mktemp -d)
cat > "$WORK_DIR/documents.jsonl" <<'JSONL'
{"schema_version":"1","external_id":"example:import-001","metadata":{"title":"Open science","year":2024},"texts":[{"text_id":"abstract-en","kind":"abstract","language":"en","content":"Open science supports reproducible research."}]}
{"schema_version":"1","external_id":"example:import-002","metadata":{"title":"Research data","year":2023},"texts":[{"text_id":"abstract-en","kind":"abstract","language":"en","content":"Research data can be shared and reused."}]}
JSONL
curl -i -X POST "$BASE/api/v2/collections/$C/imports" \
  -H 'Idempotency-Key: workflow-import-jsonl-001' \
  -F 'options={"format":"jsonl","mode":"merge"};type=application/json' \
  -F "file=@$WORK_DIR/documents.jsonl;type=application/octet-stream"
```

### Option B: JSON

One top-level object contains `schema_version` and a `documents` array:

```bash
WORK_DIR=$(mktemp -d)
cat > "$WORK_DIR/documents.json" <<'JSON'
{"schema_version":"1","documents":[{"external_id":"example:import-001","metadata":{"title":"Open science","year":2024},"texts":[{"text_id":"abstract-en","kind":"abstract","language":"en","content":"Open science supports reproducible research."}]},{"external_id":"example:import-002","metadata":{"title":"Research data","year":2023},"texts":[{"text_id":"abstract-en","kind":"abstract","language":"en","content":"Research data can be shared and reused."}]}]}
JSON
curl -i -X POST "$BASE/api/v2/collections/$C/imports" \
  -H 'Idempotency-Key: workflow-import-json-001' \
  -F 'options={"format":"json","mode":"merge"};type=application/json' \
  -F "file=@$WORK_DIR/documents.json;type=application/octet-stream"
```

**Expected for either option:** 202 with an import job ID and `Location`:

```bash
J='<IMPORT_JOB_UUID_FROM_STEP_2>'
curl "$BASE/api/v2/jobs/$J"
curl "$BASE/api/v2/jobs/$J/errors"
```

Continue after the job succeeds; inspect item errors and `global_error_code` for failures. `merge` applies PATCH semantics. `replace` applies PUT semantics to documents in the file without deleting other documents. These sample files contain **no embeddings**, so the import does not generate vectors or call a provider. JSONL errors can be reported per line; a malformed JSON container can fail globally.

## Step 3. Create or select an embedding profile

**Prerequisites:** the provider reference, model, revision and dimensions are verified. Reuse a compatible profile from workflow 1 if it already exists, or create one:

```bash
curl -i -X POST "$BASE/api/v2/embedding-profiles" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-import-profile-001' \
  -d '{"slug":"workflow-bge-m3-v1","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"http","provider_ref":"ollama-gpu-ibict","preparation":{"version":"identity-v1"}}'
```

**Expected:** 201 for a new profile. A duplicate slug or fingerprint returns 409; inspect and reuse the existing compatible profile instead of creating a duplicate.

```bash
P='<HTTP_PROFILE_UUID>'
```

An `import_only` profile supports importing externally produced vectors but cannot generate vectors or perform text search.

## Step 4. Create and provision an indexation

**Prerequisites:** collection `C` and compatible HTTP profile `P` exist; the worker can process provisioning.

```bash
curl -i -X POST "$BASE/api/v2/collections/$C/indexations" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-import-index-001' \
  -d "{\"slug\":\"abstracts\",\"profile_id\":\"$P\",\"selection\":{\"kinds\":[\"abstract\"],\"languages\":[]},\"chunking\":{\"mode\":\"none\"},\"metric\":\"cosine\",\"ann_strategy\":\"hnsw_vector\"}"
```

**Expected:** 202 containing indexation `id` and provisioning `job_id`:

```bash
I='<INDEXATION_UUID_FROM_STEP_4>'
J='<PROVISIONING_JOB_UUID_FROM_STEP_4>'
curl "$BASE/api/v2/jobs/$J"
curl "$BASE/api/v2/collections/$C/indexations/$I"
```

Continue after the provisioning job succeeds and `state` is `ready`. This configuration selects abstracts in any language, with one unit per selected text. `ready` does not mean that vectors have been generated.

## Step 5. Generate vectors for the imported documents

**Prerequisites:** the import completed, the indexation is ready, and an HTTP-capable worker can reach the profile's provider.

```bash
curl -i -X POST "$BASE/api/v2/collections/$C/indexations/$I/generation-jobs" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-import-generation-001' \
  -d '{"document_ids":[]}'
```

**Expected:** 202 with a generation job ID and `Location`:

```bash
J='<GENERATION_JOB_UUID_FROM_STEP_5>'
curl "$BASE/api/v2/jobs/$J"
curl "$BASE/api/v2/jobs/$J/errors"
```

Wait for `succeeded` and inspect the counters. The empty list selects all documents captured in the collection. For specific documents, provide up to 1000 distinct UUIDs in `document_ids`. A later run needs a new idempotency key; generation prepares missing units automatically.

## Step 6. Retrieve vectors for a document or collection

**Prerequisites:** the generation job succeeded for the imported documents. First resolve a document's internal UUID using its external ID:

```bash
curl --get "$BASE/api/v2/collections/$C/documents/by-external-id" \
  --data-urlencode 'external_id=example:import-001'
```

**Expected:** 200 with the document `id`. Save it and retrieve its vectors:

```bash
D='<DOCUMENT_UUID_RETURNED_ABOVE>'
curl "$BASE/api/v2/collections/$C/indexations/$I/documents/$D/vectors?include=texts,metadata"
curl "$BASE/api/v2/collections/$C/documents/$D/vectors"
```

**Expected:** 200 with `items[].embedding` and coverage per indexation. The second request includes all ready indexations for that document. An empty `items` list means no current vectors were returned; inspect coverage and job errors. To retrieve vectors for every document in the collection, use the paginated example below.

## Step 7. Search the imported documents

**Prerequisites:** indexation `I` is ready and contains current vectors; its HTTP provider is reachable for the text query.

```bash
curl -X POST "$BASE/api/v2/collections/$C/indexations/$I/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"text":"How can research data be shared?"},"mode":"exact","top_n":10,"result_unit":"document","include":["texts","metadata"]}'
```

**Expected:** 200 with ranked document results and distances. Exact mode reports `recall_guaranteed: true`; with fewer than 10 matching documents, `shortfall_reason` is `exhausted`. For approximate mode the ANN index must also be ready.

---

## Retrieving vectors for an entire collection

**Prerequisites:** `C` is a collection UUID, the API is reachable, and Python 3 is installed. The service exposes vector retrieval per document rather than a single collection-wide vector endpoint. This example follows document pages and vector pages, printing one JSON object per vector page. It can return large embedding arrays; use it only where output can be handled safely. It does not preserve one snapshot across pages.

```bash
export BASE C
python3 - <<'PY'
import json
import os
from urllib.parse import urlencode
from urllib.request import urlopen

base = os.environ['BASE'].rstrip('/')
collection = os.environ['C']

def get(path, parameters):
    with urlopen(base + path + '?' + urlencode(parameters), timeout=30) as response:
        return json.load(response)

document_cursor = None
while True:
    parameters = {'limit': 100}
    if document_cursor:
        parameters['cursor'] = document_cursor
    page = get(f'/api/v2/collections/{collection}/documents', parameters)
    for document in page['items']:
        vector_cursor = None
        while True:
            vector_parameters = {'limit': 100}
            if vector_cursor:
                vector_parameters['cursor'] = vector_cursor
            vectors = get(
                f"/api/v2/collections/{collection}/documents/{document['id']}/vectors",
                vector_parameters,
            )
            print(json.dumps(vectors, ensure_ascii=False))
            vector_cursor = vectors.get('next_cursor')
            if not vector_cursor:
                break
    document_cursor = page.get('next_cursor')
    if not document_cursor:
        break
PY
```

**Expected:** one or more JSON responses for each existing document, with its coverage and vectors. A collection with no documents produces no output. Use the indexation-specific document route instead if only one indexation is needed.

## Optional: build or verify the ANN index

**Prerequisites:** the indexation is ready, uses `hnsw_vector` or `hnsw_halfvec`, and a worker is enabled. First check its state:

```bash
curl "$BASE/api/v2/collections/$C/indexations/$I"
```

If `ann_state` is already `ready`, PostgreSQL maintains that index as vectors are inserted; no build is needed for each generation. If a build is needed, submit a maintenance job:

```bash
curl -i -X POST "$BASE/api/v2/collections/$C/indexations/$I/maintenance-jobs" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: workflow-build-ann-001' \
  -d '{"operation":"build_ann"}'
```

**Expected:** 202 with a job ID. Poll `/api/v2/jobs/{id}` and confirm `ann_state: "ready"` on the indexation before using `"mode":"approximate"` in the search request. Approximate search reports `recall_guaranteed: false` and can return `candidate_limit` on a shortfall; it does not silently fall back to exact mode. Index builds can consume substantial disk and WAL.

For the complete endpoint reference, response fields, limits, retention, security and recovery, see [Platform Guide](PLATFORM-GUIDE.md).
