# Phase 14: measured capacity — synthetic smoke benchmark

Recorded 2026-11-12 with `DVP_BENCHMARK=1` and the isolated `docker-compose.test.yml` fixture. This is a **small single-client smoke measurement**, not a load test or production capacity claim.

| Setting | Measured value |
| --- | --- |
| Host | Darwin arm64, 10 logical CPUs, 16 GiB host RAM; Docker container had no explicit CPU/memory limit (Docker VM limits not measured) |
| Runtime | Java 21, maximum JVM heap 4096 MiB; PostgreSQL 16.10, pgvector 0.8.0 |
| PostgreSQL settings | `shared_buffers=128MB`, `work_mem=4MB`, `max_connections=100`, autovacuum on; Docker test storage tmpfs |
| Fixture | 256 synthetic documents, one 1024-dimensional float32 vector/unit per document, one indexation, cosine HNSW; 30 sequential measured searches after three warmups |
| Synthetic insertion | 257.6 ms for 256 documents/units/vectors; direct JDBC, excludes JSON import and inference |
| HNSW build | 26.1 ms for the synthetic table |
| Storage | Vector table including indexes 3,645,440 bytes; HNSW index 2,105,344 bytes; excludes document/source registry tables |
| Exact latency | p50 5.460 ms, p95 6.152 ms, p99 6.182 ms |
| Approximate latency | p50 5.733 ms, p95 6.405 ms, p99 6.443 ms |
| Approximate recall@10 | 1.000 against exact for the repeated synthetic query |

Latency includes application service, JDBC and database work but excludes HTTP, TLS, external model calls and load-generator overhead. Percentiles are nearest-rank from only 30 observations. The HNSW index was built after insertion. Results vary with cache, hardware and background activity; the dataset and query are deliberately simple. The opt-in test prints a `DVP_BENCH` line so results can be compared without document text or credentials.

Not measured: mixed concurrent HTTP traffic, provider throughput, realistic chunk distributions or multiple indexations, multi-replica database connections, import throughput, WAL/disk saturation, autovacuum effects, FK/cascade costs, restore RPO/RTO, large-file memory usage, or 10–50 million documents. Measure those on representative infrastructure before setting capacity targets or changing pool defaults. Relevant configuration and procedures are in the [operations runbook](phase-14-operations.md).
