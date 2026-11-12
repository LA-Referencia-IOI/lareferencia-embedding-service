# Phase 14: operations and recovery

## Deployment and readiness

Use Java 21, the versioned Flyway migrations, and an administrator-installed pgvector extension >= 0.8.0. Do not grant extension installation or schema administration through the API. Start with v2 disabled, verify the legacy API, then enable v2 on the API role and start worker roles after confirming their configuration. `DVP_ROLE=API`, `WORKER`, or `ALL` selects process responsibilities; `DVP_WORKERS_ENABLED=true` is required on worker-capable processes. Verify `DVP_ENABLED`, import settings and datasource overrides explicitly for each process: Compose and standalone defaults can differ. Health details for the `dvp` component report extension version, role, enabled features and the number of configured providers without returning endpoints or credentials. Database or extension failure reports DOWN. The provider count confirms configuration only; it does **not** prove network reachability, credentials, model availability or embedding quality.

The `DVP_IMPORT_FILE_ROOT` directory must be durable and shared consistently between every API and worker that handles imports. Validate atomic same-filesystem rename, directory fsync and cross-process file locks on the real volume. The provided Compose named volume is local to one Docker host. Provision both multipart temporary space and durable volume capacity; upload and integrity verification can read or write the entire file. Keep clients, workers and storage on authenticated, access-controlled networks: collection scope is not a tenant security boundary.

Configure provider endpoint, concurrency, connect and request timeouts for each provider reference. Use the existing secret-reference environment variable mechanism; never put a token in a profile or repository file. Rotate credentials by updating the referenced secret in the deployment system, restarting/replacing the affected processes according to the deployment's availability requirements, and verifying with an authorized synthetic input. Never print secrets during diagnostics. Text generation and text search transmit source/query content to the provider; authorize the destination and data policy first. Requests may be repeated on timeout or recovery.

## Metrics and monitoring

Actuator `health` and `metrics` are exposed by the current configuration; restrict network access and detailed health to operators. The application records these low-cardinality Micrometer meters:

- `dvp.search.duration` by `mode` and `outcome`, `dvp.search.rejected` and `dvp.search.shortfalls` by `mode`; `dvp.search.candidates` is the number of **valid distinct candidates returned to the application**, not the number visited internally by HNSW.
- `dvp.provider.duration` by `outcome`; this includes admission and bounded executor/HTTP time, with no endpoint, profile, text or credential tag.
- `dvp.import.registered.bytes` summarizes bytes for newly registered durable uploads, excluding idempotent replays.
- `dvp.api.errors` by an allowlisted error `code` (unlisted codes are `other`); this counts controller-advice errors, not early request-filter rejections.
- `dvp.jobs.claimed`, `dvp.jobs.lease_lost`, `dvp.jobs.heartbeat_errors`, and `dvp.jobs.runs` by `outcome`. A completed run is a worker invocation, not necessarily a terminal job.

The existing Hikari meters describe pool occupancy when enabled by the runtime. The configured `prometheus` exposure name alone does not add a Prometheus registry dependency; confirm the actual endpoint exists before configuring a scrape. Use `/actuator/metrics/{name}` for available meters. Histograms and percentiles require suitable registry configuration and add overhead. No metric here is labeled by request, job, document, text, collection, indexation or provider ID.

Use job APIs for durable status, item errors, retries and counters. Database administrators can obtain a point-in-time queue/lease overview with a bounded operational query:

```sql
SELECT status, count(*) FROM dvp.jobs GROUP BY status;
SELECT count(*) AS expired_running FROM dvp.jobs
WHERE status = 'running' AND lease_until <= clock_timestamp();
SELECT state, ann_state, count(*) FROM dvp.indexations GROUP BY state, ann_state;
```

For a controlled inventory, DBAs may inspect `pg_stat_user_tables`/`pg_class.reltuples`, `pg_total_relation_size` and `pg_relation_size` for `dvp.vec_%` tables and their indexes. Estimates can be stale; avoid running exact counts over a large corpus in readiness checks. Queue age, retry rates, import progress, vector estimates, and disk/WAL/autovacuum thresholds need deployment-specific monitoring and alert thresholds. Current v2 metrics do **not** automatically export every required durable queue count, vector/table size, provider health or error code; add controlled collection and alerts before declaring operational readiness.

Responses include `X-Request-Id` and sanitized v2 errors; worker warnings include job IDs where available. Do not enable framework request/response-body, JDBC parameter, or wire logging for sensitive workloads. Validate the active logging configuration with synthetic canaries. Never log full document bodies, embeddings, queries, authorization headers, idempotency keys or provider responses.

## Backups and recovery

A database backup alone cannot recover retryable imports: preserve a consistent pair of PostgreSQL (including v1 tables, Flyway history, `dvp` registry, dynamic `dvp.vec_*` tables and indexes, jobs, items, counters and checkpoints) and all durable import files. Establish an explicit write boundary: stop accepting writes/uploads and stop workers, wait for in-flight work to finish or stop, then capture the database backup and file-volume snapshot. For online backups, use a separately designed, tested coordinated snapshot/PITR procedure that establishes the same consistency; independent snapshots taken at arbitrary times do not prove consistency. Capture required WAL and retention separately. Record image/application versions, extension version, backup time and filesystem snapshot identifiers without secrets.

Restore **into an isolated new database and volume** using compatible PostgreSQL tools and the required extension binaries. PostgreSQL client major versions matter: the local PostgreSQL 17 `pg_restore` emitted `transaction_timeout`, which PostgreSQL 16 rejected; matching 16 tools passed this rehearsal. Validate migration history and extension, registry/table/index relationships, job state, file sizes and SHA-256 hashes, then run v1/v2 synthetic smoke tests. A missing retained file causes import retry failure; do not silently invent or replace file bytes. Start workers only after the database and volume are reconciled. Test actual RPO/RTO and alerting on representative production infrastructure.

For application rollback, disable v2 traffic and workers, stop active uploads safely, deploy the previous compatible application and preserve v2 schema, tables, jobs and files. Do not remove the schema, dynamic tables, indexes or volumes as part of application rollback. Restore database and volume together only under an approved recovery procedure. Changes to model, revision, preparation, dimension or metric require a new immutable profile/indexation and explicit generation/import and ANN construction; v1 hashes cannot reconstruct source documents.

For an upgrade of an existing system, rehearse against an authorized copy: capture and verify backups; check installed extension and privileges; follow the existing Flyway fresh/legacy baseline rules; apply migrations; start with v2 disabled; run v1 smoke; enable v2 in stages; verify rollback while retaining v2 data. The synthetic restore rehearsal in this repository does **not** establish a migration rehearsal against an actual existing database copy.

## Reproducible disposable verification

Use only the dedicated test Compose server, never an application database. It uses tmpfs, so stopping/recreating it loses its disposable data. The two opt-in tests require a local `/dvp_test` URL and create fresh uniquely named databases; the restore test also uses matching `pg_dump`/`pg_restore` inside the test container and synthetic temporary files.

```bash
docker compose -f docker-compose.test.yml up -d --wait
DVP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/dvp_test \
DVP_RECOVERY_REHEARSAL=1 DVP_BENCHMARK=1 mvn -o clean verify
# Stop only the disposable test service when done:
docker compose -f docker-compose.test.yml stop
```

Do not use this command against production; do not replace it with volume deletion or destructive database cleanup. See [capacity measurements](phase-14-capacity.md) for the synthetic fixture, results and omissions. The restore test uses a quiescent fixture and does not prove crash consistency or production RPO/RTO.

## OpenAPI documentation endpoint

Swagger UI is served at `/swagger-ui/index.html`; the OpenAPI JSON and Swagger configuration are at `/api-docs` and `/api-docs/swagger-config`. The previous `/v3/api-docs` path is no longer served. Update API clients, reverse-proxy allowlists and documentation links when deploying this change. The UI groups v2 tags before v1 tags; enabled process roles and feature flags still determine which routes are exposed. Rolling back the application restores its prior OpenAPI path configuration; no schema migration or reindex is involved.
