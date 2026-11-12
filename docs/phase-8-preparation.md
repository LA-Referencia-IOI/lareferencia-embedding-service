# Phase 8: preparation jobs

With v2 enabled, create an asynchronous preparation job for a ready index configuration:

```http
POST /api/v2/collections/{collection_id}/indexations/{indexation_id}/preparation-jobs
Content-Type: application/json
Idempotency-Key: prepare-001

{"document_ids":[]}
```

The idempotency key is required. An omitted body, an omitted `document_ids`, or an empty list selects the whole collection; a nonempty list accepts up to 1000 unique document UUIDs, all belonging to the collection. A different request needs a different key. The response is 202 with a job ID and a `/api/v2/jobs/{job_id}` Location. An index configuration that is missing or belongs to another collection returns 404; one that is not ready returns 409. Preparation is allowed for `import_only` profiles and never invokes an inference provider.

Run a worker with `app.dvp.enabled=true`, `app.dvp.workers-enabled=true`, and role `worker` or `all`. A worker captures the collection's highest document sequence when enumeration begins, then materializes at most 100 documents per batch and 1000 active items by default. Documents created later require a subsequent job. Document changes are serialized through the document row; preparation reads the current source revision while holding that lock. A deleted document is skipped. Job and item fencing keep unit writes and counters in one transaction. Retryable database contention is rescheduled without a sleeping worker; the existing job APIs expose status and sanitized errors.

Selection matches configured kinds and languages; an empty list permits any value, and a null language matches only an empty language list. `none` emits one whole-text unit. `characters-v1` uses Unicode code-point offsets, prefers the last `\n\n` paragraph boundary in the second half of a window, does not transform CRLF, and honors configured overlap. Blank windows are skipped and emitted ordinals are consecutive. The profile's versioned document preparation and prefix are applied after slicing; SHA-256 is calculated over the resulting UTF-8 input. The original source is unchanged. Units store offsets and the hash, not a second copy of the body. Repeating the same preparation preserves the existing units. A source change deletes the old source's units and dependent vectors before its new revision is prepared.

The existing V3/V4 registry and job schema are used; no migration or v1 reindex is required. Application rollback should disable workers and restore the prior binary while retaining the schema and units. Existing units are derived records; do not drop tables or erase vectors as a rollback. Back up both source and dynamic vector tables according to the existing operations guide. Character windows do not guarantee that a provider's token limit will be met, and `app.dvp.max-text-code-points` also bounds the prepared input including its prefix.

Phases 9–11 add generation, imports and vector retrieval. Preparing a document does not generate or expose vectors.
