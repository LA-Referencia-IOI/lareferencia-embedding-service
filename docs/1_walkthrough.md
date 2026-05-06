# Embedding Cache Service Implementation

I have successfully implemented the production-ready Spring Boot application according to your architecture requirements.

## What Was Done

1. **Spring Boot Bootstrap:**
   Initialized a new Spring Boot 3+ project with Java 17, configuring dependencies for `spring-boot-starter-web`, `data-redis`, `data-jpa`, `postgresql`, `actuator`, and `lz4-java` (for xxHash).

2. **DTO Layer:**
   Created `EmbeddingRequest` and `EmbeddingResponse` to act as the REST API contract.

3. **Client Layer:**
   Implemented `EmbeddingClient` using Spring's `RestClient` to synchronously call the `provider-url` (e.g., Ollama's local instance).

4. **Cache Abstraction Layer:**
   - Implemented an `EmbeddingCache` interface defining `get` and `put`.
   - **Redis Cache:** Developed `RedisEmbeddingCache` using Spring Data Redis templates configured to store arrays of bytes.
   - **PostgreSQL Cache:** Developed `PostgresEmbeddingCache` utilizing a Spring Data JPA Repository over a custom `EmbeddingEntity` representing `id` and `bytea` binary column.
   - **Cache Configuration:** Developed `CacheConfig` that evaluates the `cache.type` property to instantiate either the Postgres or Redis cache dynamically.

5. **Service Layer (`EmbeddingService`):**
   - Applies deterministic hashing using `lz4-java`'s `XXHash64` implementation on the input text.
   - Consults the cache using a fully qualified key format: `embedding:{model}:{hash}`.
   - Converts the `List<Double>` embeddings array to raw `byte[]` binary arrays before storing them using the `EmbeddingSerializationUtil`.
   - Incorporates Micrometer for latency tracking, generation count, and cache hit/miss rates.

6. **Controller Layer (`EmbeddingController`):**
   Exposed the `POST /api/embeddings` REST endpoint using appropriate request validation.

7. **Environment Settings:**
   Provided a highly configurable `application.yml` setting defaults and extracting properties via environment variables.
   Created a `docker-compose.yml` to effortlessly spin up an instance of Redis, Postgres, and optionally Ollama for development testing.

8. **Tests:**
   Implemented unit tests `EmbeddingServiceTest` ensuring the proper functioning of the entire hash, cache HIT, and cache MISS logic flows. Removed default autoconfigured tests that would fail absent an active DB instance in a CI context without container tooling.

## Verification

- Running `./mvnw test` results in `BUILD SUCCESS`, confirming unit testing validations pass.
- Hashing uses standard double byte buffers properly serializing double precision arrays in the cache backend.

> [!TIP]
> You can now run the application with `./mvnw spring-boot:run` and toggle caches via `CACHE_TYPE=redis` or `CACHE_TYPE=postgres`. Spin up your local dependencies fast with `docker-compose up -d`.
