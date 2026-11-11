# Persistent jobs and index maintenance

The v2 job runtime stores jobs, item state, retry times, progress and checkpoints in PostgreSQL. An API process can report jobs and create an idempotent retry of failed items. The runtime only claims job kinds with a registered handler; the maintenance handler provisions and maintains index configurations. Preparation, generation and import handlers and their creation endpoints become available in their respective phases.

## Configuration and deployment

Apply Flyway migration V4 on deployment. It adds a job error code, an item retry time and indexes; existing rows receive an immediately eligible retry time. No vector reindex or data migration is required. Back up the database using your normal procedures before deployment. Rolling back application binaries does not require dropping these additive columns or indexes. Do not run old and new worker implementations concurrently without verifying their lease compatibility. Disable workers before rollback and allow leases to expire.

Set `app.dvp.enabled=true` to enable v2. Set `app.dvp.role` to `API`, `WORKER`, or `ALL` and `app.dvp.workers-enabled=true` on processes that should execute jobs. The tracked application configuration defaults to ALL with workers enabled; the example configuration defaults to API with workers disabled. Set the role and worker flag explicitly for each deployment. API and ALL serve the job API; WORKER does not serve these endpoints. Example environment variables are in `src/main/resources/application.properties.example`.

The default lease is 120 seconds, renewed every 20 seconds. Configure `DVP_LEASE_SECONDS`, `DVP_HEARTBEAT_SECONDS`, `DVP_WORKER_CONCURRENCY`, and `DVP_WORKER_POLL_MILLIS` as needed; heartbeat must be shorter than the lease and worker concurrency must not exceed `app.dvp.lock-connections`. Each process has its own bounded pools, so budget database connections across all replicas. Keep coordination and advisory-lock connections available independently of read traffic and external provider calls. Shut down a worker gracefully where possible; unfinished leases become eligible for recovery after expiration.

## API

`GET /api/v2/jobs` lists jobs using UUID keyset pagination (`limit` defaults to 100, maximum 1000; use the opaque `next_cursor` as `cursor` for the next page). Pages do not share a snapshot. `GET /api/v2/jobs/{id}` returns status and progress; `GET /api/v2/jobs/{id}/errors` returns sanitized item diagnostics with `limit` and a route-bound opaque `cursor`. `POST /api/v2/jobs/{id}/retries` requires `Idempotency-Key`, returns 202 and a Location for the child job, and retries failed items only. An active original or an unavailable handler returns 409. Normal v2 authentication and deployment access controls apply. No provider response body or source text is included in error messages; handler authors must explicitly select safe public parameters and locators.

Handlers enumerate documents in keyset batches against a captured upper sequence, with at most 100 documents per batch and 1000 queued or running items per job. Item commits require current job and item fencing tokens; result writes, counters and checkpoints belong in the same transaction. Provider operations must run outside write transactions. The dedicated document advisory lock must be held during external operations and its associated completion. Retryable transport, 429 and server errors use persisted bounded backoff; a maximum of five attempts is permitted. External calls themselves may be repeated after a crash: fencing protects database commits, not external side effects. Future handlers must implement their own safe external operation semantics and avoid exposing secrets in parameters or locators.

Import file retention and provider-specific preparation and generation handlers are delivered in their corresponding later phases. Creating an index configuration provisions its table but does not prepare texts or generate embeddings.

## Index configurations and maintenance

An index configuration binds a collection and an immutable profile, selection, chunking recipe, metric and ANN strategy. `POST /api/v2/collections/{collection}/indexations` requires `Idempotency-Key` and returns 202, a provisioning job ID and the configuration Location. The worker provisions a UUID-derived table with a source-unit foreign key; `ready` means the table is available, not that vectors have been generated. List and inspect configurations using the corresponding GET routes. Creating an index does not call an embedding provider.

With workers enabled, submit `POST /api/v2/collections/{collection}/indexations/{id}/maintenance-jobs` with `Idempotency-Key` and `{"operation":"build_ann"}` or `{"operation":"reconcile"}`. Inspect the returned job using `/api/v2/jobs/{job-id}`. Concurrent index creation runs on one dedicated maintenance connection per process, outside database transactions. Its advisory lock serializes maintenance for an index configuration across processes; coordination and write pools remain separate. ANN builds can require substantial disk space and WAL; deploy with sufficient capacity. The table remains available for exact access while ANN builds; exact-search endpoints are delivered in a later phase.

Storage uses float32 vectors with 1–16000 dimensions. Native HNSW is allowed to 2000 dimensions, halfvec HNSW to 4000, and `none` permits exact-only access up to 16000. No automatic precision change occurs. The approved halfvec admission policy rejects float32 values whose conversion overflows and rejects cosine vectors that become zero after conversion; float32 storage is unchanged. Future generation, import and query handlers must apply `HalfvecPolicy` before admitting values. Reconciliation and build check existing rows before declaring a halfvec index ready.

Reconciliation checks the table's required columns and vector dimension, primary key, exact scope constraint, cascading source-unit foreign key and ANN index state, including the halfvec expression dimension. Partial ANN indexes are not accepted as ready. An absent or incompatible table is marked failed. An invalid ANN index is marked failed; it is never silently reported healthy. Concurrent PostgreSQL builds can leave an invalid index behind after interruption. An operator must inspect and repair that index through an approved database procedure before requesting another build; this service does not remove it automatically. Existing vectors and v1 store tables are not modified.

The `capabilities` fields report physical index readiness; vector retrieval and search endpoints are introduced in later phases. `counters.vectors_estimated` comes from PostgreSQL statistics, carries `is_estimate: true`, and can be null until statistics are available. `measured_at` reports the last analyze time when available; listing does not count every vector.

The existing V3/V4 schema is used; no migration or reindex is required to deploy this phase. Existing v1 behavior and already stored vectors are unchanged. For rollback, stop workers and deploy the previous binaries; retain the v2 registry and tables. Phase 8 and later supply preparation, generation, import and search; an index configuration alone does not populate vectors.

## HTTP request and response examples

Examples assume v2 is enabled and use illustrative UUIDs and timestamps. Substitute IDs returned by your server. For asynchronous execution, run an instance with workers enabled; a 202 response means the job was accepted, not that it finished. Use a different idempotency key for each different operation.

### POST: create an index configuration and provisioning job

```http
POST /api/v2/collections/11111111-1111-4111-8111-111111111111/indexations
Content-Type: application/json
Idempotency-Key: index-create-001

{"slug":"abstracts","profile_id":"33333333-3333-4333-8333-333333333333","selection":{"kinds":["abstract"],"languages":[]},"chunking":{"mode":"none"},"metric":"cosine","ann_strategy":"hnsw_vector"}
```

```http
HTTP/1.1 202 Accepted
Location: /api/v2/collections/11111111-1111-4111-8111-111111111111/indexations/44444444-4444-4444-8444-444444444444
Content-Type: application/json

{"id":"44444444-4444-4444-8444-444444444444","collection_id":"11111111-1111-4111-8111-111111111111","slug":"abstracts","profile_id":"33333333-3333-4333-8333-333333333333","metric":"cosine","ann_strategy":"hnsw_vector","state":"provisioning","ann_state":"pending","created_at":"2026-11-10T12:00:00Z","selection":{"kinds":["abstract"],"languages":[]},"chunking":{"mode":"none"},"profile":{"id":"33333333-3333-4333-8333-333333333333","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"import_only"},"capabilities":{"exact":false,"approximate":false},"counters":{"is_estimate":true,"vectors_estimated":null,"measured_at":null},"job_id":"66666666-6666-4666-8666-666666666666"}
```

The collection and profile must already exist. Poll the returned `job_id`; `Location` identifies the index configuration, not the job.

### GET: list index configurations

```http
GET /api/v2/collections/11111111-1111-4111-8111-111111111111/indexations?limit=100
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"items":[{"id":"44444444-4444-4444-8444-444444444444","collection_id":"11111111-1111-4111-8111-111111111111","slug":"abstracts","profile_id":"33333333-3333-4333-8333-333333333333","metric":"cosine","ann_strategy":"hnsw_vector","state":"ready","ann_state":"pending","created_at":"2026-11-10T12:00:00Z","selection":{"kinds":["abstract"],"languages":[]},"chunking":{"mode":"none"},"profile":{"id":"33333333-3333-4333-8333-333333333333","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"import_only"},"capabilities":{"exact":true,"approximate":false},"counters":{"is_estimate":true,"vectors_estimated":null,"measured_at":null}}],"next_cursor":null}
```

### GET: retrieve an index configuration

```http
GET /api/v2/collections/11111111-1111-4111-8111-111111111111/indexations/44444444-4444-4444-8444-444444444444
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id":"44444444-4444-4444-8444-444444444444","collection_id":"11111111-1111-4111-8111-111111111111","slug":"abstracts","profile_id":"33333333-3333-4333-8333-333333333333","metric":"cosine","ann_strategy":"hnsw_vector","state":"ready","ann_state":"pending","created_at":"2026-11-10T12:00:00Z","selection":{"kinds":["abstract"],"languages":[]},"chunking":{"mode":"none"},"profile":{"id":"33333333-3333-4333-8333-333333333333","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"import_only"},"capabilities":{"exact":true,"approximate":false},"counters":{"is_estimate":true,"vectors_estimated":null,"measured_at":null}}
```

### POST: request maintenance

```http
POST /api/v2/collections/11111111-1111-4111-8111-111111111111/indexations/44444444-4444-4444-8444-444444444444/maintenance-jobs
Content-Type: application/json
Idempotency-Key: ann-build-001

{"operation":"build_ann"}
```

```http
HTTP/1.1 202 Accepted
Location: /api/v2/jobs/77777777-7777-4777-8777-777777777777
Content-Type: application/json

{"id":"77777777-7777-4777-8777-777777777777","kind":"maintenance","status":"queued"}
```

Use `{"operation":"reconcile"}` with a fresh idempotency key for reconciliation. `build_ann` returns 409 when the index configuration is not ready or has strategy `none`.

### GET: list jobs

```http
GET /api/v2/jobs?limit=100
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"items":[{"id":"77777777-7777-4777-8777-777777777777","kind":"maintenance","status":"succeeded","collection_id":"11111111-1111-4111-8111-111111111111","indexation_id":"44444444-4444-4444-8444-444444444444","parent_job_id":null,"parameters":{"operation":"build_ann"},"counters":{},"created_at":"2026-11-10T12:01:00Z","finished_at":"2026-11-10T12:02:00Z","global_error_code":null}],"next_cursor":null}
```

If `next_cursor` is non-null, pass it unchanged as the `cursor` query parameter on the next request. The list contains only public job fields, ordered by ascending UUID.

### GET: inspect a job

```http
GET /api/v2/jobs/77777777-7777-4777-8777-777777777777
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id":"77777777-7777-4777-8777-777777777777","kind":"maintenance","status":"succeeded","collection_id":"11111111-1111-4111-8111-111111111111","indexation_id":"44444444-4444-4444-8444-444444444444","parent_job_id":null,"parameters":{"operation":"build_ann"},"counters":{},"created_at":"2026-11-10T12:01:00Z","finished_at":"2026-11-10T12:02:00Z","global_error_code":null}
```

### GET: list item errors

```http
GET /api/v2/jobs/77777777-7777-4777-8777-777777777777/errors?limit=100
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"items":[],"next_cursor":null}
```

A failed item may include `item_key`, `document_id`, a sanitized `locator`, `code`, `message` and `attempts`. A global maintenance failure can have no item errors; inspect the job's `global_error_code`.

### POST: retry failed items

A retry requires a terminal job with failed items and a registered handler. The following is a response shape for such a job; it does not imply that the maintenance job shown above has failed items.

```http
POST /api/v2/jobs/88888888-8888-4888-8888-888888888888/retries
Idempotency-Key: failed-items-retry-001
```

```http
HTTP/1.1 202 Accepted
Location: /api/v2/jobs/99999999-9999-4999-8999-999999999999
Content-Type: application/json

{"id":"99999999-9999-4999-8999-999999999999","kind":"maintenance","status":"queued","collection_id":"11111111-1111-4111-8111-111111111111","indexation_id":"44444444-4444-4444-8444-444444444444","parent_job_id":"88888888-8888-4888-8888-888888888888","parameters":{"operation":"reconcile"},"counters":{},"created_at":"2026-11-10T12:03:00Z","finished_at":null,"global_error_code":null}
```

An active job returns 409 `job_active`; a terminal job without failed items returns 409 `no_retryable_failures`. To repeat a failed global maintenance operation, submit a new maintenance request with a new key.
