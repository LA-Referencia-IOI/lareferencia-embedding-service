# Phase 11: per-document vector retrieval

With v2 enabled, the following read-only routes return stored current vectors without calling a model provider:

```text
GET /api/v2/collections/{collection}/documents/{document}/vectors
GET /api/v2/collections/{collection}/document-vectors?external_id=...
GET /api/v2/collections/{collection}/indexations/{indexation}/documents/{document}/vectors
GET /api/v2/collections/{collection}/indexations/{indexation}/document-vectors?external_id=...
```

Use the exact, URL-encoded external ID. For example:

```bash
curl --get 'http://localhost:8989/api/v2/collections/COLLECTION_UUID/document-vectors' \
  --data-urlencode 'external_id=example:article-001' \
  --data-urlencode 'limit=100' \
  --data-urlencode 'include=texts,metadata'
```

The default page size is 100, maximum 1000. Pass `next_cursor` as `cursor` with the same route, document and `include` selection. Pages are ordered by indexation UUID and unit UUID; each request uses a new database snapshot, so changes between pages may affect results. Vectors are always returned. Metadata and source/unit texts are omitted unless requested by `include`. Descriptors report the profile and exact document coverage, including unprepared texts and missing prepared vectors.

An existing document with no vectors returns 200 and an empty `items` list; a missing or out-of-scope document returns 404. A specific indexation that is not ready returns 409 `indexation_unavailable`; the all-indexations routes skip those indexations and list them in `unavailable_indexation_ids`. A saturated read pool returns 503. Reads use the dedicated read pool and a bounded repeatable-read transaction; coverage queries run once per ready indexation, so a large number of indexations or units can increase latency. Monitor the configured read timeout and pool capacity.

This phase adds no migration and does not change v1 data or require reindexing. Roll back by deploying the previous application version; existing documents, vectors and indexations remain in place. Retrieval never generates or modifies embeddings.
