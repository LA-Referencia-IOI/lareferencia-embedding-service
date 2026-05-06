# Implement Spring Boot Embedding Cache Service

This implementation plan outlines the creation of a production-ready Spring Boot 3+ application that provides a REST API for computing and caching text embeddings. It uses a deterministic cache layer with a strategy pattern to support Redis and PostgreSQL.

## User Review Required

- **PostgreSQL pgvector vs byte[]**: The requirements mention both `pgvector` and "Store embeddings as binary (`byte[]`)". Since this service acts purely as a key-value cache (`hash` -> `embedding`), we don't necessarily need to perform similarity search (which is what `pgvector` provides). Storing as `bytea` in Postgres is more efficient for a direct lookup cache and strict `byte[]` storage. I plan to use standard `bytea` for the Postgres implementation. Let me know if you explicitly need the column to be of type `vector` from the `pgvector` extension instead.

## Open Questions

- None at the moment.

## Proposed Changes

### Project Setup
- Initialize a new Spring Boot 3+ project with Java 17+ using Maven.
- Add dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-redis`, `spring-boot-starter-data-jpa`, `postgresql`, `org.lz4:lz4-java` (for xxHash), `spring-boot-starter-actuator` (for metrics).

### Core Components

#### [NEW] `EmbeddingRequest` and `EmbeddingResponse` DTOs
- DTOs for the `/api/embeddings` REST endpoint.

#### [NEW] `EmbeddingController`
- Exposes `POST /api/embeddings` endpoint. Maps the request and returns the embedding response.

#### [NEW] `EmbeddingService`
- Orchestrates the flow:
  1. Calculate xxHash of the input string.
  2. Build cache key `embedding:{model}:{hash}`.
  3. Query `EmbeddingCache`.
  4. On MISS: Call `EmbeddingClient`, cache the binary result, and return.
  5. Include Micrometer metrics for cache HIT/MISS rates and generation latencies.

#### [NEW] `EmbeddingCache` (Interface)
- Interface defining the cache contract: `Optional<byte[]> get(String key)` and `void put(String key, byte[] embedding)`.

#### [NEW] `RedisEmbeddingCache`
- Implements `EmbeddingCache` using Spring Data Redis `RedisTemplate<String, byte[]>`.

#### [NEW] `PostgresEmbeddingCache`
- Implements `EmbeddingCache` using Spring Data JPA. 
- Entity will have `id` (cache key) and `embedding` (byte array).

#### [NEW] `EmbeddingClient`
- Communicates with the external embedding API (e.g., Ollama) using Spring's `RestClient`. Configured with timeouts and simple retries.

#### [NEW] `CacheConfig`
- Responsible for instantiating the correct `EmbeddingCache` bean based on the `cache.type` application property (using `@ConditionalOnProperty`).

#### [NEW] `application.yml` and `docker-compose.yml`
- Environment-based configurations for both Redis and Postgres.
- Docker compose file to easily spin up Redis and PostgreSQL locally for testing.

## Verification Plan

### Automated Tests
- Write unit tests for `EmbeddingService` to verify the cache HIT and MISS paths using mock caches and clients.
- Verify xxHash generation is deterministic.

### Manual Verification
- Start the application with Redis configured via `docker-compose`.
- Send a request to `/api/embeddings` and observe a cache MISS and the embedding generation.
- Send the exact same request again and observe a cache HIT (and zero latency to the embedding API).
- Switch `cache.type` to `postgres` and repeat the process to verify the PostgreSQL cache implementation.
