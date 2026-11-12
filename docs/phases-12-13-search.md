# Phases 12–13: semantic search

Search one ready indexation with an external vector or a text query. Text search requires an HTTP profile and sends the prepared query to its configured embedding provider; confirm that provider is authorized to receive the query. External vectors must come from the same model, revision and preparation space as the indexation; the service validates dimensions and numeric values but cannot verify provenance.

```bash
curl -X POST 'http://localhost:8989/api/v2/collections/COLLECTION_UUID/indexations/INDEXATION_UUID/search' \
  -H 'Content-Type: application/json' \
  -d '{"query":{"text":"acesso aberto à pesquisa"},"top_n":10,"result_unit":"document","mode":"exact","include":["texts","metadata"]}'
```

For an externally generated query vector use `{"query":{"vector":[...]},"mode":"exact"}` with exactly the configured number of float32-compatible components. Exactly one of `query.text` and `query.vector` is required. Defaults are `top_n: 10`, `result_unit: "document"`, `mode: "approximate"` and no projections. `top_n` ranges from 1 to 100; `result_unit` is `document` or `unit`; `include` accepts `texts` and `metadata`. Search responses never return embedding arrays.

Exact search scans all current vectors and ranks each document using its globally closest unit before applying the result limit. It returns `recall_guaranteed: true` and `shortfall_reason: "exhausted"` if fewer results exist. Approximate search requires a valid, ready HNSW index, expands its candidate budget as needed up to 10,000, reranks full float32 vectors and returns `recall_guaranteed: false`; a shortfall is reported as `candidate_limit`. The selected unit is the best among retrieved candidates, and approximate recall is not guaranteed. For halfvec ANN, incompatible query vectors return `halfvec_not_representable` without modifying stored float32 vectors. There is no silent fallback to exact mode.

One bounded repeatable-read snapshot covers candidate filtering, ranking and projections for each request. Search admits 2 exact and 16 approximate requests per instance by default (`app.dvp.exact-concurrency`, `app.dvp.approximate-concurrency`), with respective query timeouts of 60 and 10 seconds. Excess requests receive 429; a query timeout returns 503 `search_timeout`. Provider calls use the separate bounded inference executor, configured provider timeouts and provider concurrency. Requests sharing a deployment also share the configured read pool. These defaults are initial budgets, not a production capacity guarantee.

An unavailable indexation returns 409 `indexation_unavailable`; an unavailable or invalid ANN index returns 409 `approximate_search_unavailable`. Text against `import_only` returns 409 `profile_not_executable`; externally supplied vectors still support exact search. Do not interpret `ann_state=ready` as a recall guarantee. Search does not write vectors or invoke v1 storage. No schema migration or reindex is required for this change. To roll back, deploy the earlier application version and retain the existing schema, indexations, source data and vector tables.
