# Phase 5: embedding profiles and provider registry

With `DVP_ENABLED=true`, `POST /api/v2/embedding-profiles` creates a profile, `GET /api/v2/embedding-profiles` lists profiles, and `GET /api/v2/embedding-profiles/{id}` retrieves one. POST requires an `Idempotency-Key`; the same key and request replay the original response and Location. There are no update or delete routes. Profile creation performs no inference and makes no provider request.

```bash
curl -X POST http://localhost:8989/api/v2/embedding-profiles \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: profile-create-001' \
  -d '{"slug":"research-v1","model":"bge-m3","model_revision":"deployment-2026-11","dimensions":1024,"execution_mode":"http","provider_ref":"local","preparation":{"version":"identity-v1"}}'
```

Create an `import_only` profile by omitting `provider_ref`. Such profiles retain the declared provenance but cannot call inference; the platform cannot verify which weights produced an imported vector. For HTTP profiles, the reference must name a configured provider. Model and revision must be nonblank and at most 255 Unicode code points; dimensions are 1–16000. The administrator must assign a new revision and profile when weights or the embedding space change. A slug or fingerprint already in use returns 409 with the existing profile URI.

Fingerprinting uses SHA-256 over RFC 8785 canonical JSON containing `model`, `model_revision`, `dimensions`, `execution_mode`, `provider_ref` and `preparation`. Before hashing, missing prefixes become empty strings and the import-only provider becomes null. Slug and credentials are excluded; provider references remain distinct even if they currently point at the same endpoint. `preparation.version` is required and supports `identity-v1` and `legacy-normalizer-v1`; each prefix defaults to empty and is limited to 8192 code points. Preparation applies to each unit after chunking; document or query prefix is then prepended. Query preparation does not chunk. The prepared-input code-point limit must be supplied by the caller and is enforced without truncation.

Configure providers independently under `app.dvp.providers`. For example, in an external configuration file:

```yaml
app:
  dvp:
    providers:
      local:
        type: OLLAMA
        endpoint: http://localhost:11434/api/embed
        connect-timeout: 5s
        request-timeout: 30s
        concurrency: 4
        batch-size: 128
      hosted:
        type: OPENAI_COMPATIBLE
        endpoint: https://provider.example/v1/embeddings
        secret-ref: EMBEDDING_PROVIDER_TOKEN
        connect-timeout: 5s
        request-timeout: 30s
        concurrency: 4
        batch-size: 128
```

The full endpoint must be HTTP(S), without embedded user credentials, query, or fragment. `secret-ref` names an environment variable containing the bearer token; it is resolved only for a provider call and is never stored in the profile or returned by the API. Provider type, endpoint and credentials are runtime settings, not proof of model identity. Rotate credentials independently; change the revision and profile whenever a deployment changes vector space. Do not send confidential content to an external provider without appropriate authorization.

Provider concurrency and batch sizes are per provider, per process. The shared `app.dvp.inference-concurrency` bounds active calls per instance across providers; excess work fails with 503 rather than occupying database write or coordination pools. Budget aggregate calls as the sum across all API/worker replicas before enabling inference consumers, taking each provider's own limits into account. Request/connect timeouts bound individual calls; a timeout may still have incurred provider charges. The caller must finish its database transaction before invoking the provider. Future persistent workers own retry and backoff; profile creation does not retry or call providers. OpenAI-compatible responses are checked and reordered by index; Ollama `/api/embed` responses are checked in input order. All returned vectors must match the dimensions and contain finite float32 values. Avoid HTTP/JDBC payload tracing in environments containing private content or credentials.

The existing V3 registry table and uniqueness constraints are reused; no schema migration or legacy reindex is required. V1 providers and stored vectors are unchanged. Rollback can disable v2 or restore the earlier application while retaining the V3 schema and profiles. Profile records already in use must not be mutated in the database; restore from a consistent backup if necessary. Phase 5 adds no generation, search, jobs, or data migration; those remain separate phases.

For verification, start an isolated pgvector test database as described in [Phases 1–3 operations](phases-1-3-operations.md#reproducible-verification) and run `DVP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/dvp_test mvn -o clean verify`. The provider tests use local synthetic HTTP endpoints and do not contact configured external services.
