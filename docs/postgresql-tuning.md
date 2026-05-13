# PostgreSQL Tuning for Embedding

This document provides tuning recommendations for the PostgreSQL database used by the **lareferencia-embedding-service**, specifically optimized for large datasets (50 GB+).

## Access Pattern

The application uses PostgreSQL exclusively for two operations:

| Operation        | Description                                                                          |
| ---------------- | ------------------------------------------------------------------------------------ |
| **Insert**       | `INSERT INTO embeddings_<storageId> (id, embedding) ... ON CONFLICT (id) DO NOTHING` |
| **Lookup by PK** | `SELECT ... FROM embeddings_<storageId> WHERE id IN (...)`                           |

> [!IMPORTANT]
> Embedding lookups use the primary key (`id TEXT`) — no vector similarity search is performed at query time. HNSW/IVFFlat indexes are created automatically by the application (configurable via `app.pgvector.index-type`), but are **not used** for cache lookups. Set `app.pgvector.index-type: NONE` to skip vector index creation and save storage/insert time if similarity search is not needed.

---

## Table Schema

Each storage gets its own table named `embeddings_<storageId>` (UUID with dashes replaced by underscores), created in the configured schema (default: `public`):

```sql
CREATE TABLE IF NOT EXISTS public.embeddings_550e8400_e29b_41d4_a716_446655440000 (
    id          TEXT PRIMARY KEY,                        -- xxHash64 hex of normalized input
    embedding   vector(<dimensions>) NOT NULL,           -- actual dimension set at storage creation
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

- **PK B-tree index** on `id` is automatically created and is the only index needed for cache lookups.
- **HNSW vector index** is created by default (configurable). Each row stores an N-dimension embedding vector (~10 KB for 2560 dimensions).

---

## 1. PostgreSQL Server Configuration (`postgresql.conf`)

### Memory Settings

```ini
# Set to ~25% of total RAM (example: 16 GB RAM → 4 GB)
shared_buffers = 4GB

# Set to ~75% of total RAM — helps the query planner estimate cache availability
effective_cache_size = 12GB

# Per-operation memory for sorts/hashes (generous since we have simple queries)
work_mem = 64MB

# For VACUUM, CREATE INDEX, and other maintenance operations
maintenance_work_mem = 512MB
```

### Memory Scaling Reference

Scale these values according to your server's total RAM:

| Setting                | Formula  | 8 GB RAM | 16 GB RAM | 32 GB RAM |
| ---------------------- | -------- | -------- | --------- | --------- |
| `shared_buffers`       | ~25% RAM | 2 GB     | 4 GB      | 8 GB      |
| `effective_cache_size` | ~75% RAM | 6 GB     | 12 GB     | 24 GB     |
| `maintenance_work_mem` | ~3% RAM  | 256 MB   | 512 MB    | 1 GB      |
| `max_wal_size`         | fixed    | 2 GB     | 4 GB      | 4 GB      |

### WAL (Write-Ahead Log) — Critical for Insert Performance

```ini
# Larger WAL buffers reduce I/O for write-heavy workloads
wal_buffers = 64MB

# Reduces fsync frequency — trades crash durability for insert speed
# Safe when data can be regenerated (embedding cache is regenerable)
synchronous_commit = off

# Increase checkpoint interval to reduce I/O spikes
checkpoint_completion_target = 0.9
max_wal_size = 4GB
min_wal_size = 1GB
```

> [!TIP]
> `synchronous_commit = off` is the single most impactful change for insert performance. Since the embedding cache is regenerable (the provider can always re-generate embeddings), the small risk of losing a few recent writes on a crash is acceptable.

### Connections

```ini
# HikariCP manages pooling on the app side; ensure enough server-side connections
max_connections = 100
```

---

## 2. HikariCP Connection Pool (`application.yml`)

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 300000 # 5 minutes
      connection-timeout: 10000 # 10 seconds
      max-lifetime: 600000 # 10 minutes
```

---

## 3. Table-Level Optimizations (SQL)

Run these directly on the PostgreSQL database. Replace `<table>` with the actual storage table name (e.g. `embeddings_550e8400_e29b_41d4_a716_446655440000`):

```sql
-- Verify the PK index exists (should show <table>_pkey)
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = '<table>';

-- Drop any unnecessary vector indexes if similarity search is not used
-- DROP INDEX IF EXISTS idx_<table>_vector;

-- Set fillfactor to 90 — leaves room for HOT updates from ON CONFLICT
ALTER TABLE public.<table> SET (fillfactor = 90);

-- Ensure TOAST storage is optimal for large vector data
-- EXTENDED = compress + move out-of-line (default, ideal for vectors)
ALTER TABLE public.<table> ALTER COLUMN embedding SET STORAGE EXTENDED;

-- Update planner statistics after bulk loads
ANALYZE public.<table>;
```

---

## 4. Autovacuum Tuning

For high-insert tables, autovacuum should run more frequently to prevent table bloat:

```sql
ALTER TABLE public.<table> SET (
    autovacuum_vacuum_scale_factor = 0.05,      -- trigger at 5% dead tuples (default: 20%)
    autovacuum_analyze_scale_factor = 0.02,      -- re-analyze at 2% changes (default: 10%)
    autovacuum_vacuum_cost_delay = 10            -- less delay = more aggressive vacuum
);
```

---

## 5. Periodic Maintenance

```sql
-- Run periodically (weekly/monthly) to reclaim space from dead tuples
VACUUM (VERBOSE) public.<table>;

-- After large bulk inserts, rebuild table to optimize storage layout
-- WARNING: Takes an exclusive lock — schedule during maintenance windows
VACUUM FULL public.<table>;

-- Alternative: pg_repack for online table rewriting (no lock)
-- pg_repack -t <table> -d embeddings
```

---

## 6. Monitoring Queries

### Table Size Breakdown

```sql
SELECT
    pg_size_pretty(pg_total_relation_size('public.<table>')) AS total_size,
    pg_size_pretty(pg_relation_size('public.<table>'))       AS table_size,
    pg_size_pretty(pg_indexes_size('public.<table>'))        AS index_size;
```

### Cache Hit Ratio (should be > 95%)

```sql
SELECT
    relname,
    round(100.0 * heap_blks_hit / NULLIF(heap_blks_hit + heap_blks_read, 0), 2) AS cache_hit_pct
FROM pg_statio_user_tables
WHERE relname = '<table>';
```

### Row Count and Dead Tuples

```sql
SELECT n_live_tup, n_dead_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables
WHERE relname = '<table>';
```

### Long-Running Queries

```sql
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE state != 'idle'
  AND now() - pg_stat_activity.query_start > interval '5 seconds'
ORDER BY duration DESC;
```

---

## Summary

| Concern              | Recommendation                                           |
| -------------------- | -------------------------------------------------------- |
| **No vector search** | Do not create HNSW/IVFFlat indexes                       |
| **Insert speed**     | `synchronous_commit = off` + larger WAL settings         |
| **Lookup speed**     | PK B-tree index is optimal for `findById` (O(log n))     |
| **Memory**           | `shared_buffers = 25%` RAM to keep PK index pages cached |
| **Large table**      | `fillfactor = 90`, tune autovacuum, monitor dead tuples  |
| **Connection pool**  | HikariCP `maximum-pool-size = 20`                        |
| **Maintenance**      | Regular `VACUUM`, `ANALYZE` after bulk loads             |


## Speed up PostgreSQL/pgvector for search
- [https://aiven.io/developer/postgresql-pgvector-indexes](https://aiven.io/developer/postgresql-pgvector-indexes)