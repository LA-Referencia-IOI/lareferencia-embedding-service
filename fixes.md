# Code review findings

Review date: November 2, 2026.

Implementation update (November 4, 2026). Phase 1 implemented fixes 1–7 with unit regression tests and PostgreSQL/pgvector tests. The original review below is retained as history. Its pending labels and locale failure reflect the initial review. See the [implementation plan](docs/coding/IMPLEMENTATION_PLAN.md) for current status and approvals and the [operations guide](docs/coding/phases-1-3-operations.md) for migration and rollback. The v1 code now lives under `org.lareferencia.embedding.v1`.

The original findings came from static analysis except for the test failure reported at the end. Each finding includes a proposed fix and a verification scenario.

## 1. Race condition in per-key locks

**Priority:** high. **Original status:** pending.

**Code:** `src/main/java/org/lareferencia/embedding/util/PerKeyLockManager.java`, methods `lockAll` and `LockSet.close`.

Closing a lock set calls `unlock()` and removes the lock from the map if `hasQueuedThreads()` is false. A second thread may already have acquired the lock with no threads left waiting. If the entry disappears, a third thread can create another lock for the same key and enter the protected section concurrently. A second race exists between looking up the lock and starting to wait for it.

**Impact.** Duplicate provider calls for the same text and loss of mutual exclusion within a service instance.

**Proposed fix.** Track owners and waiters atomically before removing an entry, or use a fixed set of locks. Keep a global acquisition order to avoid deadlocks. Local locks coordinate threads within an instance.

**Verification.** Force concurrent acquisitions during release and check that only one protected section is active per key. Cover batches with overlapping keys.

## 2. Missing Docker configuration in a clean checkout

**Priority:** high. **Original status:** pending.

**Code:** `Dockerfile`, `docker-compose.yml` and `src/main/resources/application.example.yml`.

Spring Boot requires an active configuration file. The Dockerfile leaves `application.example.yml` as an example. Compose variables such as `DB_URL`, `DB_USER` and `EMBEDDING_PROVIDER` rely on its placeholders.

**Impact.** The documented startup can fail without datasource configuration or ignore the provider selection. A local, Git-ignored `application.yml` can hide the issue.

**Proposed fix.** Ship a secret-free base configuration, load the configuration explicitly or use directly bound variables such as `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` and `APP_EMBEDDING_PROVIDER_TYPE`. Review the variables in the README and leave the default API key empty.

**Verification.** Build and start from a checkout without `application.yml`, with PostgreSQL and the vector extension available. Verify connectivity, the effective port and provider selection from environment variables.

## 3. Providers can be mixed within a store

**Priority:** high. **Original status:** pending.

**Code:** `EmbeddingService.generateEmbeddings`, `StoreService` and `EmbeddingProviderService`.

A store records a provider, while generation uses the globally configured provider. Reusing or adding vectors does not check that the providers match. After switching providers and restarting, one store can contain vectors from different providers for the same model and dimensions.

**Impact.** The vector space can become inconsistent and responses can name a provider different from the one that produced cached vectors.

**Proposed fix.** Define a provider policy. Under a single-provider policy, reject incompatible stores. To support multiple providers, resolve the client from the stored provider and include it in store identity and uniqueness. Changing providers should require a compatible store or an explicit migration.

**Verification.** Create a store with one provider, change the configuration and verify cache hits and new texts. Reject silent mixing of vectors.

## 4. Store cache diverges from transactions and replicas

**Priority:** medium. **Original status:** pending.

**Code:** `StoreService.createStore`, `loadStoresIntoCache` and `EmbeddingRepository.ensureStoreTableExists`.

`createStore` publishes the store in memory before commit. A failed commit can leave a cache entry without a database row. The repository also marks tables initialized before the transaction that creates them completes. Stores are loaded at startup, so stores created on another instance can be missing from the local cache.

**Impact.** Rollbacks can leave phantom stores or tables marked initialized. Replicas can return 404 for stores created elsewhere.

**Proposed fix.** Publish and invalidate cached state according to the transaction outcome. Add cross-instance coherence through database lookups on cache misses and controlled refresh, or shared invalidation.

**Verification.** Force a commit failure and check that no phantom entries or table markers remain. Create a store on one instance and retrieve and use it from another.

## 5. In-memory and PostgreSQL uniqueness rules differ

**Priority:** medium. **Original status:** pending.

**Code:** `StoreService.createStore`, `StoreEntity` and `GlobalExceptionHandler`.

The precheck uses `equalsIgnoreCase`. Database constraints on `name` and `(model, embedding_dimension)` apply different rules. The lookup and insert are separate operations. Concurrent requests can both pass the check, case variants can make stores ambiguous, and uniqueness violations can return HTTP 500.

**Impact.** Inconsistent validation, ambiguous lookups and server errors for conflicts that should return HTTP 409.

**Proposed fix.** Define case sensitivity per identifier and apply it in lookups and PostgreSQL constraints. For case-insensitive comparison, use normalized keys or unique indexes on `lower(...)`. Return 409 for the relevant uniqueness violations and preserve other integrity errors.

**Verification.** Create stores concurrently with matching names and model/dimension pairs, including case variants. The database should keep one entry per allowed combination and return 409 for duplicates.

## 6. Embeddings are associated by response order

**Priority:** medium. **Original status:** pending.

**Code:** `EmbeddingProviderService.toEmbeddingList` and the hash association in `EmbeddingService.generateEmbeddings`.

The parser walks `data` in arrival order and ignores `index`. An out-of-order provider response with valid indexes can associate vectors with the wrong texts and persist those associations in the cache.

**Impact.** Incorrect responses and cache entries even when dimensions and result counts are valid.

**Proposed fix.** Restore input order using `index` and validate its type, range, uniqueness and completeness. Define behavior for providers that omit it.

**Verification.** Simulate out-of-order results and duplicate, missing or out-of-range indexes. Check text-to-vector associations in both responses and cache writes. Reject ambiguous payloads as provider errors.

## 7. Size formatting depends on the locale

**Priority:** low. **Original status:** pending, reproduced in tests.

**Code:** `StatusService.formatBytes` and `StatusServiceTest.combinesSystemAndDatabaseStatus`.

`"%.2f %s".formatted(...)` uses the default locale. In the review environment it returned `4,00 KiB`, while the test expected `4.00 KiB`.

**Proposed fix.** Use an explicit locale, for example `String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex])`, to keep the API format stable.

**Verification.** Check the result in Spanish and English locales. Restore the original locale after a test that changes global state.

## Initial validation

`mvn test` on Java 21 ran **29 tests, with 28 passing and 1 failing**, with no errors or skips. The failure was the size-formatting issue in finding 7.

The initial coverage was mainly unit tests. The original review called for concurrency and PostgreSQL/pgvector integration tests and a container startup check from a clean checkout. It did not exercise real providers.

The proposed sequence was Docker configuration, locking, provider identity, transaction and replica coherence, uniqueness, result ordering, and size formatting. Each fix should be validated before it is marked complete.
