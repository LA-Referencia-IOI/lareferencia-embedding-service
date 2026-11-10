# LA Referencia Embedding Service

A Spring Boot service that exposes an OpenAI-style embeddings API backed by a
PostgreSQL + pgvector cache. Clients create a named store for a provider model
and dimensions, then request embeddings by passing that model and dimensions.

The service normalizes input text, hashes it deterministically, reuses cached
vectors when available, and only calls the configured embedding provider for
cache misses.

## Features

- **OpenAI-compatible response shape:** Returns `object`, `data`, and `usage`
  fields in the style expected by common embeddings clients.
- **Store-based caching:** Each store binds a user-facing name to a unique
  provider model and dimensions pair, provider type, and dedicated pgvector table.
- **Deterministic cache keys:** Text is normalized with `TextNormalizer` v1 and
  hashed with xxHash64 before lookup.
- **Batch support:** Accepts one string or an array of strings and preserves
  the original input order in the response.
- **Provider selection:** Supports `OLLAMA`, `OPENAI`, and `OPENROUTER`.
- **Optional vector indexes:** Store creation can request a pgvector HNSW or
  IVFFLAT index for similarity-search use cases.
- **Consistent store lookup:** Indexed database lookups make committed stores
  visible across replicas; dynamic-table readiness is cached only after commit.
- **Additive v2 foundation:** Optional Collections API with transactional
  idempotency, strict JSON, cursor pagination and isolated resource pools.
- **Operational endpoints:** Actuator health, metrics, Prometheus, and
  application status endpoints expose service and database usage.

## Requirements

- Java 21+
- Maven 3.9+
- PostgreSQL 16+ with the pgvector extension installed
- An embedding provider compatible with the configured provider endpoint

For local development, `docker-compose.yml` can start the service and a
`pgvector/pgvector:0.8.0-pg16` database.

## Configuration

The tracked `src/main/resources/application.properties` loads on a clean checkout.
Use environment variables for local or container runs. The optional
`application.example.yml` documents equivalent overrides; ignored
`src/main/resources/application.yml` is excluded from clean JAR and Docker builds.
To load a private configuration explicitly, use an external file with
`--spring.config.additional-location=file:/path/to/application.yml`.

Docker Compose and the active configuration use the environment variables shown
below. Spring Boot relaxed binding also accepts equivalent property-style
environment variables such as `APP_EMBEDDING_PROVIDER_TYPE`.

| Property | Common env var | Default | Description |
| :-- | :-- | :-- | :-- |
| `server.port` | `SERVER_PORT` | `8989` | HTTP port |
| `app.embedding.provider.type` | `EMBEDDING_PROVIDER` | `OLLAMA` | Provider selector: `OLLAMA`, `OPENAI`, or `OPENROUTER` |
| `app.embedding.provider.ollama-url` | `OLLAMA_URL` | `http://localhost:11434/v1/embeddings` | Ollama-compatible embeddings endpoint |
| `app.embedding.provider.openai-url` | `OPENAI_URL` | `https://api.openai.com/v1/embeddings` | OpenAI embeddings endpoint |
| `app.embedding.provider.openrouter-url` | `OPENROUTER_URL` | `https://openrouter.ai/api/v1/embeddings` | OpenRouter embeddings endpoint |
| `app.embedding.provider.api-key` | `EMBEDDING_PROVIDER_API_KEY` | empty | Bearer token sent when configured |
| `app.embedding.provider.connect-timeout` | `OLLAMA_CONNECT_TIMEOUT` | `5s` | HTTP connection timeout |
| `app.embedding.provider.request-timeout` | `OLLAMA_REQUEST_TIMEOUT` | `30s` | HTTP read/request timeout |
| `app.embedding.max-batch-size` | `EMBEDDING_MAX_BATCH_SIZE` | `128` | Maximum input strings per embeddings request |
| `app.embedding.max-input-length` | `EMBEDDING_MAX_INPUT_LENGTH` | `8192` | Maximum characters per input string |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/embeddings` | PostgreSQL JDBC URL |
| `spring.datasource.username` | `DB_USER` | `embeddings` | Database user |
| `spring.datasource.password` | `DB_PASSWORD` | empty in application; `embeddings` in local Compose | Database password |
| `app.pgvector.schema-name` | `APP_PGVECTOR_SCHEMA_NAME` | `public` | Schema used for vector tables |
| `app.pgvector.initialize-schema` | `APP_PGVECTOR_INITIALIZE_SCHEMA` | `true` | Whether the service creates per-store vector tables automatically |
| `app.pgvector.schema-validation` | `APP_PGVECTOR_SCHEMA_VALIDATION` | `false` | Whether to verify that the configured schema exists |
| `app.pgvector.index-type` | `APP_PGVECTOR_INDEX_TYPE` | `HNSW` in active configuration; `NONE` in Java-only defaults | Index type used when `createVectorIndex` is true |
| `app.pgvector.distance-type` | `APP_PGVECTOR_DISTANCE_TYPE` | `COSINE_DISTANCE` | Operator class used for vector indexes |

Provider model names are passed through to the upstream provider. A model may be
registered with multiple dimensions, but each `model` + `dimensions` pair can
belong to only one store. Store names are local service identifiers and may
contain letters, numbers, spaces, hyphens, and underscores, up to 60 characters.

For local Ollama usage, leave `EMBEDDING_PROVIDER_API_KEY` empty. When the value
is present, the service sends it as a Bearer token to the selected provider.

## Database Setup

The database must have the pgvector extension enabled before creating stores:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

The service creates one vector table per store when the store is created. Table
names are generated from the store UUID, for example:

```text
public.embeddings_33a4f614_da2b_452b_9f80_35c8df2177ce
```

If you use a schema other than `public`, create it yourself and configure
`app.pgvector.schema-name`. The service validates identifier safety, but it does
not create database schemas or the pgvector extension.

## Running Locally

### Docker Compose

Start the app and local pgvector database:

```bash
docker compose --profile postgres up --build
```

With the default Compose settings:

- The service listens on `http://localhost:8989`.
- PostgreSQL listens on `localhost:5432`.
- Ollama is expected on the host at `http://host.docker.internal:11434/v1/embeddings`.

Enable pgvector in the Compose database:

```bash
docker compose exec postgres psql -U embeddings -d embeddings -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### Maven

Set database connection variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`) as needed,
enable the vector extension administratively, then run:

```bash
mvn spring-boot:run
```

Build a runnable JAR:

```bash
mvn clean package
java -jar target/lareferencia-embedding-service-0.0.1-SNAPSHOT.jar
```

## API

Swagger UI is available when the service is running:

```text
http://localhost:8989/swagger-ui.html
```

The active default port is `8989`; override it with `SERVER_PORT` if needed.

### Create a Store

```http
POST /api/v1/stores
```

Creates a named store and its pgvector table. The service does not discover the
dimensions from the provider during store creation, so set `dimensions` to the
embedding size returned by the selected model.

Request:

```json
{
  "name": "bge-m3-1024",
  "model": "bge-m3",
  "dimensions": 1024,
  "createVectorIndex": false
}
```

Fields:

| Field | Required | Description |
| :-- | :-- | :-- |
| `name` | yes | Store name used later in embeddings requests. Allows letters, numbers, spaces, hyphens, and underscores. |
| `model` | yes | Provider model name, for example `bge-m3`, `nomic-embed-text`, or `text-embedding-3-small`. |
| `dimensions` | yes | Positive embedding vector size for the provider model. |
| `createVectorIndex` | no | When `true`, creates a pgvector ANN index according to `app.pgvector.index-type`. |

Response:

```json
{
  "id": "33a4f614-da2b-452b-9f80-35c8df2177ce",
  "name": "bge-m3-1024",
  "model": "bge-m3",
  "provider": "ollama",
  "embeddingDimensions": 1024,
  "createdAt": "2026-06-30T22:29:57.915501Z"
}
```

Notes:

- Store names are checked case-insensitively for duplicates at creation time.
- The same provider model can be reused with different dimensions.
- The same provider model and dimensions pair cannot be registered twice.
- If `createVectorIndex` is `true` and the configured index type is `HNSW`,
  the service skips the vector index for dimensions above 2000 because pgvector
  does not support HNSW indexes above that limit.

### List Stores

```http
GET /api/v1/stores
```

Returns committed stores from the database, ordered by UUID.

Example response:

```json
[
  {
    "id": "33a4f614-da2b-452b-9f80-35c8df2177ce",
    "name": "bge-m3-1024",
    "model": "bge-m3",
    "provider": "ollama",
    "embeddingDimensions": 1024,
    "createdAt": "2026-06-30T22:29:57.915501Z"
  }
]
```

### Get Status

```http
GET /api/v1/status
```

Returns host resource usage plus PostgreSQL store and embedding-table usage.
Byte values are returned both as raw numbers and formatted display strings.

Example response:

```json
{
  "timestamp": "2026-08-07T16:14:58.113Z",
  "system": {
    "cpu": {
      "availableProcessors": 8,
      "usagePercent": 12.5,
      "freePercent": 87.5,
      "systemLoadAverage": 1.82
    },
    "ram": {
      "totalBytes": 17179869184,
      "usedBytes": 8589934592,
      "freeBytes": 8589934592,
      "usagePercent": 50.0,
      "freePercent": 50.0,
      "total": "16.00 GiB",
      "used": "8.00 GiB",
      "free": "8.00 GiB"
    },
    "disk": {
      "totalBytes": 494384795648,
      "usedBytes": 247192397824,
      "freeBytes": 247192397824,
      "usableBytes": 247192397824,
      "usagePercent": 50.0,
      "freePercent": 50.0,
      "total": "460.42 GiB",
      "used": "230.21 GiB",
      "free": "230.21 GiB",
      "usable": "230.21 GiB",
      "volumes": [
        {
          "path": "/",
          "totalBytes": 494384795648,
          "usedBytes": 247192397824,
          "freeBytes": 247192397824,
          "usableBytes": 247192397824,
          "usagePercent": 50.0,
          "freePercent": 50.0,
          "total": "460.42 GiB",
          "used": "230.21 GiB",
          "free": "230.21 GiB",
          "usable": "230.21 GiB"
        }
      ]
    }
  },
  "database": {
    "name": "embeddings",
    "sizeBytes": 104857600,
    "size": "100.00 MiB",
    "storeCount": 2,
    "embeddingTableCount": 2,
    "embeddingTables": [
      {
        "schema": "public",
        "table": "embeddings_33a4f614_da2b_452b_9f80_35c8df2177ce",
        "storeId": "33a4f614-da2b-452b-9f80-35c8df2177ce",
        "storeName": "bge-m3-1024",
        "model": "bge-m3",
        "embeddingDimensions": 1024,
        "tableSizeBytes": 6291456,
        "indexesSizeBytes": 1048576,
        "totalSizeBytes": 7340032,
        "estimatedRows": 12000,
        "tableSize": "6.00 MiB",
        "indexesSize": "1.00 MiB",
        "totalSize": "7.00 MiB"
      }
    ]
  }
}
```

### Generate or Reuse Embeddings

```http
POST /api/v1/embeddings
```

The request `model` field is the provider model name. The service uses
`model` + `dimensions` to find the registered store and its dedicated cache table.

Single input:

```json
{
  "model": "bge-m3",
  "dimensions": 1024,
  "input": "Este es un texto academico"
}
```

Batch input:

```json
{
  "model": "bge-m3",
  "dimensions": 1024,
  "input": ["Texto 1", "Texto 2"]
}
```

Response:

```json
{
  "storeId": "33a4f614-da2b-452b-9f80-35c8df2177ce",
  "storeName": "bge-m3-1024",
  "model": "bge-m3",
  "provider": "ollama",
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "index": 0,
      "embedding": [0.0012, -0.045]
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "total_tokens": 0
  }
}
```

Input behavior:

- `input` must be a nonblank string or an array of nonblank strings.
- `model` and `dimensions` must match one registered store.
- Batch size is limited by `app.embedding.max-batch-size`.
- Each input length is limited by `app.embedding.max-input-length`.
- Text normalization trims, joins hyphenated line breaks, lowercases, replaces
  line breaks with spaces, collapses whitespace, and applies Unicode NFC
  normalization before hashing.

Usage behavior:

- OpenAI and OpenRouter usage values are copied from the provider response when
  present.
- Ollama usage values are currently returned as zero.

## Error Responses

Errors are returned as `ApiErrorResponse` objects with timestamp, status, error,
message, path, and details fields.

Common statuses:

- `400 Bad Request`: invalid JSON, validation error, blank input, oversized
  batch, oversized input, or dimensions mismatch.
- `404 Not Found`: no store was found for the requested model and dimensions.
- `409 Conflict`: duplicate store name/model and dimensions, or a store whose persisted provider differs from the configured provider.
- `502 Bad Gateway`: upstream embedding provider failure.
- `500 Internal Server Error`: unexpected application error.

## Testing

Run the test suite:

```bash
mvn test
```

Current coverage includes:

- DTO validation for store names.
- Text normalization rules.
- Store creation, duplicate detection, listing, and dimensions checks.
- Embedding cache lookup, cache-miss generation, ordering, and inconsistent
  provider dimensions.
- Repository SQL generation for pgvector table and index creation.
- Status response composition for database and embedding table metrics.
- Provider request authentication, response parsing, usage parsing, and error
  wrapping.

## v2 Collections and upgrades

Set `DVP_ENABLED=true` to expose `/api/v2/collections`. V2 is disabled by default;
this release implements collection create/list/get and document/source-text
lifecycle operations and immutable embedding profiles. Indexations, jobs, imports
and search are planned in later phases. See [Phase 4 documents](docs/phase-4-documents.md)
for document usage and [Phase 5 profiles](docs/phase-5-profiles.md) for provider
configuration, fingerprinting, capacity limits and rollback.

Creating a collection requires an `Idempotency-Key` header:

```bash
curl -X POST http://localhost:8989/api/v2/collections \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: research-create-001' \
  -d '{"slug":"research","name":"Research"}'
```

Use a new key for a different create request. Reusing the same key with the same
body replays the original response; reusing it with different content returns 409.

See [Phases 1–3 operations](docs/phases-1-3-operations.md) for the Collections
contract, limits/pools, explicit legacy Flyway baseline, non-destructive rollback,
and the opt-in real PostgreSQL integration-test commands. Existing installations
must follow that baseline procedure before starting this version: automatic
baselining is disabled, and Hibernate now validates migration-owned tables.

The v1 store identity and xxHash/text-normalization behavior are preserved.
Case-insensitive store uniqueness and model lookup use PostgreSQL `lower(...)`
consistently. A store from a different configured provider returns 409 before
cache reads or inference; do not repoint an existing store at another vector
space. No historical cache is converted into documents or automatically reindexed.
