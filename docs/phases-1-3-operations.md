# Phases 1–3: operation, compatibility and verification

This release preserves the v1 embedding API, introduces an additive `dvp` registry
and implements v2 Collections. Phase 4 also exposes the [document and source-text
API](phase-4-documents.md); profile/indexation/job/import/search APIs and workers
belong to later phases. No UI is included.
The existing deployment access boundary applies; a collection is not a security tenant.

## Java package boundaries

V1 controllers, DTOs, entities, repositories, services, exceptions, utilities and
provider/vector properties are under `org.lareferencia.embedding.v1`; v2 domain and
contract code is under `org.lareferencia.embedding.v2`. Version-specific tests use
matching packages. The root contains the application entry point and shared
`config` infrastructure (database wiring, OpenAPI and route-error dispatch);
`integration` test helpers/migration tests are shared by both versions. V1 and v2
controller advice is scoped to its own version. Shared routing preserves each
version's 404 shape without a v1-to-v2 implementation dependency.

This changes Java package names, not HTTP paths, JSON field names, configuration
prefixes, store table names or migration versions. Update external Java imports
and any class-name-based logging configuration if they refer to the old packages;
HTTP embedding/indexing clients require no migration.

## Startup and configuration

Java 21 and Maven are unchanged. The tracked `application.properties` loads without
an ignored file; the default port is 8989. Clean JAR builds and Docker images exclude
ignored `src/main/resources/application.yml`. Run `mvn clean package` when upgrading
to remove any old copied resource from `target/`. Supply private configuration via
environment variables or `--spring.config.additional-location=file:/path/to/application.yml`.
No provider key is sent unless configured. The example file is documentation, not
an automatically loaded configuration.

Set `DB_URL` (JDBC URL), `DB_USER` and `DB_PASSWORD` for the target database, or the
equivalent `SPRING_DATASOURCE_*` properties. Compose uses directly bound Spring
datasource variables; its local defaults are separate from application defaults.
An administrator installs the extension before store creation. Enabled v2 requires
pgvector >=0.8.0 and fails startup for a missing/incompatible extension; the Java
pgvector driver version is not the database extension version. The deployment/test
image is pinned to `pgvector/pgvector:0.8.0-pg16`.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT current_setting('server_version'), extversion
FROM pg_extension WHERE extname = 'vector';
```

`DVP_ENABLED=false` is the default. Set it to `true` to enable Collections.
`DVP_ROLE=API` is the default; `WORKER` suppresses v2 HTTP routes and `ALL` allows
them. `DVP_WORKERS_ENABLED` defaults to false; no worker engine is implemented in
Phases 1–3, so keep it false. These flags do not remove schema/data or disable v1.
The registry migrations run even with v2 disabled, making deployment additive.

## Fresh and existing database migrations

Flyway owns static schema changes; Hibernate uses `validate`, not `update`.
Flyway history lives in `public.flyway_schema_history`, and cleaning is disabled.

| Migration | Effect |
| --- | --- |
| V1 | Fresh `stores` table with the existing v1 columns/constraints. |
| V2 | Duplicate preflight and unique indexes on `lower(name)` and `(lower(model), embedding_dimension)`. No rows or vector tables are rewritten. |
| V3 | Separate `dvp` static registry for the complete design. No dynamic vector table or unfinished API is created. Adds persisted replay headers and optional job linkage for active-job key retention. |

For a fresh database, start normally after installing the extension. Flyway applies
V1–V3 and Hibernate validates `stores`. For a populated legacy installation without
Flyway history, normal startup deliberately fails rather than guessing a baseline.
Do the following on an isolated copy first; schedule the actual deployment separately:

1. Take a restorable backup including legacy vector tables. Use your normal PostgreSQL
   service/credential configuration; for example `pg_dump --format=custom --file=/safe/backup/location/before-v2.dump`.
2. Inventory `stores` and compare it with `StoreEntity`/V1: UUID ID; name/model varchar(60);
   provider varchar(20); nullable integer embedding_dimension; required created_at timestamptz;
   existing primary key and original uniqueness constraints. Inventory per-store tables and
   keep a row/vector sample for comparison. Resolve drift explicitly before proceeding.
3. Check case-insensitive duplicates without changing data:

   ```sql
   SELECT lower(name), count(*) FROM stores GROUP BY lower(name) HAVING count(*) > 1;
   SELECT lower(model), embedding_dimension, count(*) FROM stores
   WHERE embedding_dimension IS NOT NULL
   GROUP BY lower(model), embedding_dimension HAVING count(*) > 1;
   ```

   Both queries must be empty. V2 also enforces this preflight and aborts transactionally
   if duplicates exist. An operator chooses a non-destructive resolution; this release
   does not merge stores or guess vector provenance.
4. Only after that verification, explicitly opt into baseline **version 1** for the
   existing installation, using the same configured database environment:

   ```bash
   java -jar target/lareferencia-embedding-service-0.0.1-SNAPSHOT.jar \
     --spring.flyway.baseline-on-migrate=true \
     --spring.flyway.baseline-version=1 \
     --app.dvp.enabled=false
   ```

   This records V1 as the operator-verified legacy baseline, skips recreating `stores`,
   and applies V2–V3. Do not baseline at V2/V3: that would skip required upgrades.
   Remove the temporary baseline-on-migrate override for subsequent startups.
5. Verify migration history, row/vector samples, case-insensitive duplicate 409s,
   `/api/v1/stores`, embeddings/cache behavior and `/api/v1/status`. Enable v2 only
   after those checks; create/list/get a collection and verify its row/replay record.

Dynamic v1 tables retain their names and float32 values. Table-readiness markers are
published only after commit, and store listings/lookups read committed database state
rather than a permanent per-process registry. Model lookup/uniqueness now consistently
uses database `lower(...)`; model/dimension uniqueness remains global in v1.
Changing the configured provider makes incompatible stores return 409 before either
cache hits or misses. It does not relabel or migrate their vectors. Restore the matching
provider or plan an explicit migration; externally changed model weights are not detected.

## Collections API

All paths below begin with `/api/v2`. JSON uses snake_case and RFC 3339 UTC timestamps;
unknown/duplicate fields, wrong scalar types, trailing JSON and unknown/repeated query
parameters return 400. Display names preserve case and Unicode and need not be unique;
slugs are unique lowercase ASCII matching `^[a-z0-9][a-z0-9_-]*$`, up to 100 characters.
`name` is a required string up to 200 Unicode code points. No name trimming or slug
normalization is implicit.

| Method/path | Contract |
| --- | --- |
| `POST /collections` | Body `{"slug":"research","name":"Investigación Abierta"}`; mandatory `Idempotency-Key`; 201 plus `Location: /api/v2/collections/{uuid}`. |
| `GET /collections` | Optional `limit` (default 100, 1–1000) and opaque `cursor`. Ascending PostgreSQL UUID order, keyset pagination and no global count. |
| `GET /collections/{uuid}` | 200 for an existing collection; 404 otherwise. Invalid UUID is 400. |

A collection is `{"id":"<uuid>","slug":"research","name":"Investigación Abierta","created_at":"<UTC timestamp>"}`.
A list is `{"items":[...],"next_cursor":null}`; pass a non-null cursor unchanged on
the next request. It is bound to its path/selection and is not an authorization token.
Pages do not share a snapshot; stabilize concurrent writes to enumerate a fixed set.
Each individual read uses a bounded repeatable-read transaction on the read pool.

An identical key/content replay returns the original status, body and Location.
The method/canonical path is the scope; normalized query and RFC 8785 canonical body
identify content. A changed body under the same key is 409 `idempotency_conflict`.
An overlapping request waits at most 5 seconds for the transaction lock, then returns
409 `idempotency_in_progress` with `Retry-After: 1`. A duplicate slug is 409
`collection_exists`; no duplicate row or partial response reservation is committed.
Keys persist at least 7 days and while their linked job is active. Terminal/no-job
expired keys may be removed lazily on access or by the bounded `purgeExpired` service
hook; no public cleanup endpoint exists. Later job maintenance can schedule the hook.

V2 errors contain `timestamp`, `status`, `code`, `message`, `request_id`, `details`;
`X-Request-Id` matches the generated identifier. Parse/database diagnostics do not
return bodies, SQL, provider keys or private exception text. A request above the byte
limit is 413, including when its declared size is absent; database/pool unavailability
is 503. V1 retains its separate response/error shapes. Swagger/OpenAPI at `/v3/api-docs`
and `/swagger-ui.html` documents the enabled routes; disabled v2 has no Collections
controller. Document endpoints use the same `If-Match` helpers; see the
[Phase 4 document contract](phase-4-documents.md).

## Resource ownership and initial limits

V2 pools are created only when enabled. All use the configured database credentials;
no additional service or broker is introduced. Per-instance defaults:

| Resource/property | Limit/routing |
| --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | 10 write connections, shared by legacy JPA and atomic v2 JDBC writes. |
| `app.dvp.read-connections` | 8 search/read connections; read transactions use repeatable read; statement/read timeout 60 s. |
| `app.dvp.coordination-connections` | 2 reserved for short claims/heartbeats/diagnostics; statement timeout 5 s. |
| `app.dvp.lock-connections` | 4 separate connections reserved for future session advisory locks. |
| `app.dvp.connection-timeout-ms` | 1000 ms borrow timeout for v2 pools; primary pool has its own Hikari connection-timeout (1000 ms default). |
| `app.dvp.inference-concurrency` | 4 executor threads with no queued backlog; excess submission rejects immediately. Future provider clients/jobs must use this budget. |
| `app.dvp.write-timeout-seconds` | 30 s for atomic v2 write transactions. |
| `app.dvp.max-request-bytes` | 16 MiB for current v2 requests, checked by header and streaming count. |
| `app.dvp.idempotency-retention-days` | At least 7; active linked jobs prevent expiry. |

The upper bound is 24 pooled database connections per enabled instance with these
settings (10 + 8 + 2 + 4), multiplied by replicas; idle minimum is zero. Inference
has a separate executor, not a database pool. `writeTransactions` uses the primary
JPA transaction manager and its same JDBC connection; `readTransactions` uses the
read datasource. Coordination/lock connections never split a document's atomic
result/checkpoint/counter transaction. Later feature phases must repeat contention
checks with actual jobs/search before exposure. Phase 14 tunes these existing budgets.

`DvpProperties` also publishes the later-feature defaults from the plan: 1000 texts,
2 million code points/text, 256 KiB metadata, 8192 code points/query, exact/approximate
search 60/10 s and 2/16 admissions, 10 GiB files, internal file root `./data/imports`,
24-hour temporary and 30-day terminal retention, 120-second leases, 20-second
heartbeats, 1000 active items, batches of 100 documents/128 provider inputs and
5/30-second provider connect/request timeouts. These do not expose or implement the
later APIs. No halfvec admission policy or dynamic ANN is implemented before phase 7.

## Reproducible verification

Unit tests require no database or provider. Real-database tests are explicitly opt-in:

```bash
mvn test

docker compose -f docker-compose.test.yml up -d --wait
DVP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/dvp_test mvn clean verify
docker compose -f docker-compose.test.yml stop
```

The test server is pinned PostgreSQL 16/pgvector 0.8.0, bound to localhost, using
trust authentication solely for disposable fixtures. `TestDatabase` creates new
UUID-named databases on the explicitly selected `/dvp_test` server and never reuses
application databases. Test Compose uses tmpfs; stopping it discards its test data.
Choose `DVP_TEST_DB_PORT` and the matching JDBC port if 55439 is occupied. CI must set
`DVP_TEST_DB_URL` and run the integration suite; without it those tests are skipped
and a unit-only pass is not integration evidence. No real provider calls are made.

Checks cover: ordered/repeated cached v1 batches, normalization, provider mismatch,
response indices, locales, lock handoffs; real uniqueness races, replica visibility,
DDL/vector rollback, explicit legacy baseline and failed-migration rollback; v2
canonicalization, cursors/preconditions, idempotency races/timeouts/retention and
resource saturation; and Collections Unicode/limits/strict JSON, concurrent writes,
pagination, replay, error contracts and generated OpenAPI.

## Rollback and reindex

Disable v2 routes (`DVP_ENABLED=false`) and keep workers disabled. Roll back to the
previous validated application version while preserving `dvp`, Flyway history,
legacy stores and all vector tables. The original application only maps `stores`;
it must not manage v2 entities. Rehearse on a copy first. Do not run Flyway clean,
destructive down migrations, drop schemas or delete vectors as an application rollback.
A data rollback requires your consistent backup/restore procedure and separate approval.

No vector reindex or inference is required by V1–V3. The lower-case uniqueness indexes
are administrative store indexes, not ANN rebuilds. Existing hashes cannot reconstruct
source documents and are not imported into v2. Future changes in profile/model revision,
preparation, dimensions or metric require a new immutable profile/indexation and
explicit generation/import; they are not performed by this release.
