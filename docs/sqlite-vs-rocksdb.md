# SQLite vs RocksDB

For a scenario ranging from small caches (100 thousand vectors) to a massive volume of 15 million (150 GB), the choice between **SQLite** and **RocksDB** depends on how much you prioritize **development simplicity (SQL)** versus **raw read/write performance (key-value)**.

Both are **embedded** (they do not require a server) and **persistent**. Here is the detailed comparison:

---

## 1. SQLite

SQLite is used as an embedded relational database in a single file, accessed through JDBC. Embeddings are stored as `BLOB` values.

### Advantages

- **Ease of Use (SQL):** You use syntax you already know. You can run `SELECT embedding FROM embeddings WHERE hash_key = ?`.
- **Java Ecosystem:** JDBC integration is standard and very stable.
- **Management:** The database is a `.db` file. Moving, copying, or backing it up is as simple as running `cp`.
- **Flexibility:** If you need to add extra columns in the future, such as `source_url` or `last_accessed`, it is just an `ALTER TABLE`.

### Disadvantages

- **Performance at Scale:** At 15 million records, SQLite's B-tree index may become slower than RocksDB for key lookups.
- **Write Concurrency:** SQLite locks the file for writes. Since the current implementation does not configure `WAL`, multiple concurrent writes can become a bottleneck earlier, with errors such as `database is locked`.
- **SQL Overhead:** The SQL parser adds a small amount of latency compared to direct byte access.

---

## 2. RocksDB

RocksDB is a very high-performance key-value storage library optimized for SSDs.

### Advantages

- **Massive Scalability:** It was built to handle billions of keys. For 15 million vectors, it is comfortably within its intended scale.
- **Write Performance (LSM-Tree):** It is much faster than SQLite for inserts because it turns random writes into sequential disk writes.
- **Native Compression:** The project already enables LZ4 compression, reducing disk usage without complicating the API.
- **Efficiency for raw cache access:** Since access is `get(key)` and `put(key, value)`, it fits the service's current pattern well.

### Disadvantages

- **Complexity in Java:** It requires JNI (Java Native Interface). The API is based on `byte[]`, so the application must manually serialize and deserialize embeddings.
- **Opacity:** There is no easy visual interface for inspecting the data. You cannot simply open DBeaver and run a `SELECT` without specific tooling.
- **Lack of SQL:** It is strictly `get(key)` and `put(key, value)`. If you need more complex filtering in the future, that logic must be implemented in the application.

---

## Performance Comparison by Scenario

| Scenario                  | SQLite                  | RocksDB               | Verdict                                |
| ------------------------- | ----------------------- | --------------------- | -------------------------------------- |
| **Small (100k - 2M)**     | **Excellent**           | Very Good             | SQLite is more practical here.         |
| **Massive (15M / 150GB)** | Medium (May suffer I/O) | **Excellent**         | RocksDB handles the large file better. |
| **Parallel Writes**       | Low (Queueing)          | **Very High**         | RocksDB is unmatched for ingestion.    |
| **Development**           | Fast (JDBC/SQL)         | Slower (Manual Bytes) | SQLite wins on productivity.           |

---

## Which One Should You Choose for Your Project?

If your monthly indexing workflow is **highly parallel** with many processes requesting and storing vectors at the same time, **RocksDB** is the technically correct choice to avoid file locking issues.

However, if you value **simplicity** and want a local backend without a server, **SQLite** is currently the simplest option in the project. For larger volumes, it is still viable, but the lack of `WAL` and the serialized write model should be considered before using it as the primary backend for heavy ingestion.
