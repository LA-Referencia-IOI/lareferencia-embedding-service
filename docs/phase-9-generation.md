# Phase 9: deferred embedding generation

With v2 enabled, enqueue generation for a ready index configuration with an HTTP-capable profile:

```http
POST /api/v2/collections/{collection_id}/indexations/{indexation_id}/generation-jobs
Content-Type: application/json
Idempotency-Key: generate-001

{"document_ids":[]}
```

The idempotency key is required. An omitted body, omitted `document_ids` or empty array selects the collection; a nonempty list accepts up to 1000 unique document UUIDs from that collection. The response is 202 with a job ID and a `/api/v2/jobs/{job_id}` Location. Replaying the same key and body returns the original response; a different body requires a new key. A missing or out-of-scope index configuration returns 404, one not ready returns 409, and an `import_only` profile returns 409 `profile_not_executable`.

Run an enabled worker with role `worker` or `all`; the common [jobs operations guide](phases-5-7-persistent-jobs.md) documents roles, leases, status and errors. Generation prepares missing units as described in [Phase 8](phase-8-preparation.md), then reads units without current vectors. Before HTTP, it holds a dedicated per-indexation/document advisory lock and commits database work; no write transaction is held across a provider call. Inputs are deduplicated within each batch; a current vector in the same index configuration can be reused only after comparing the complete prepared input. No vector is reused across index configurations or from v1. Each unit retains its own vector row.

A batch contains at most 16 inputs and is also capped by `app.dvp.provider-batch-size` and the provider's configured batch size. Provider-specific and shared inference limits remain per instance. Configure the endpoint, credential reference, connect/request timeouts and per-provider concurrency as described in [Phase 5](phase-5-profiles.md); budget the sum across replicas before enabling production workers. Source content is sent to the profile's configured provider. Confirm the provider is authorized for that data before starting generation.

Before publishing, the worker locks the document and verifies the job and item leases, advisory session, source revisions, unit identities and prepared hashes. An edited or deleted source discards the late result and records `source_changed`; metadata-only changes do not change the source snapshot. Edits invalidate earlier units and vectors through the existing source lifecycle. A lost lease or advisory session prevents publication. HTTP may be repeated after a timeout or worker failure, including billable calls; it is not exactly once.

Vectors must have the profile's dimension and finite float32 values; cosine additionally rejects an all-zero vector. For `hnsw_halfvec`, values whose half conversion overflows, and cosine vectors that become all zero after conversion, fail with `halfvec_not_representable`; stored values remain float32. Invalid provider vectors are recorded as permanent item errors. Transport failures, capacity exhaustion, upstream 429 and 5xx responses use the existing persisted retry schedule, up to five attempts, with jitter and `Retry-After` capped at 300 seconds. Errors are sanitized. The job counters include prepared units and generated/reused vector rows, skipped items and retries.

Generation uses the existing V3/V4 schema and per-indexation tables; no new migration or v1 reindex is required. Different weights, model revision, dimensions or preparation require a new immutable profile and index configuration. To roll back, disable workers and restore the prior application while retaining metadata, source data, units and vector tables. Recovery from data changes requires a consistent backup; do not drop tables or indexes as a rollback. Phases 10 and 11 supply imports and vector retrieval separately.
