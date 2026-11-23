# From a harvester export to a v2 vector collection

This workflow uses a LA Referencia metadata export to calculate vectors with EmbeddingGemma 2 and load a new PostgreSQL collection. Calculation and import can run on separate machines. Importing an existing vector package requires Python and access to PostgreSQL.

## 1. Export metadata

The examples for Brazil (`BR`) and Argentina (`AR`) use `classification-embeddings-v1.json`. The filename retains its `v1` suffix. Check that the packaged `mapping.json` declares `"version": "2.0"`.

| Field in `data` | XOAI selector |
|---|---|
| `titles` | `dc.title.*` |
| `descriptions` | `dc.description.*` |
| `keywords` | `dc.subject.*` |

Each occurrence preserves its value, language and qualifier when available. The calculator uses the qualifiers to identify abstracts among the descriptions. Adjust the configuration path in the harvester shell and export each network with `--variant original`.

```text
export-mapping-validate --mapping /config/exports/classification-embeddings-v1.json
export-metadata-preview --network BR --variant original --mapping /config/exports/classification-embeddings-v1.json --limit 5
export-metadata --network BR --variant original --format jsonl --mapping /config/exports/classification-embeddings-v1.json --partition-by network --compression gzip --output /exports
export-metadata --network AR --variant original --format jsonl --mapping /config/exports/classification-embeddings-v1.json --partition-by network --compression gzip --output /exports
```

For another network, substitute its acronym and preview the titles and abstracts. Keep the entire `exp-…` directory with a `COMPLETE` manifest, its JSONL parts, `mapping.json` and `records.sqlite`. The calculator and importer open the index in read-only mode to look up the record, part and line.

## 2. Calculate vectors

Run from the repository root on branch `v2` using the environment described in the [Python scripts guide](../scripts/python/README.md).

```bash
META=/path/to/harvester-export
VECTORS=/path/to/export-vectors
REVISION=f9972ac22e696a105c482202646979e33e4b5a1e

python scripts/python/compute_metadata_embeddings.py "$META" "$VECTORS" \
  --revision "$REVISION"
```

Choose a new output directory. This recipe takes the first nonblank title and joins descriptions whose qualifier starts with `abstract`. When there is no abstract, it uses the title as the body text. For a dedicated `abstracts` field, add `--abstract-field abstracts --abstract-qualifier ''`.

CUDA and BF16 are the defaults. For CPU, use `--device cpu --dtype float32`. Use `--offline` if the pinned model revision is already cached.

The output contains a manifest and one GZIP JSONL vector part per metadata part. For example, line 42 of `BR/part-000001.jsonl.gz` corresponds to line 42 of `BR/part-000001.embeddings.jsonl.gz`. Each line contains an array with a 768-dimensional vector at position 0, identified as `title-abstract` in the manifest.

## 3. Validate and import

For editions generated with metadata tools and `--format sqlite`, follow the [direct SQLite import guide](../scripts/python/README.md#import-a-sqlite-vector-edition-into-v2). The commands here use the harvester export and its JSONL vector package.

Use Python 3.11 or later. If you move to a different machine, set `META` and `VECTORS` to the directories on that machine.

```bash
python -m pip install -r scripts/python/requirements-import.txt

python scripts/python/import_metadata_embeddings.py "$META" "$VECTORS" \
  --collection-slug my-collection --collection-name "My collection" \
  --dry-run
```

The dry run validates hashes, line order, texts and vectors without connecting to PostgreSQL. To load, use PostgreSQL with pgvector and the migrated v2 schema. Set `DATABASE_URL` to its DSN using the permissions listed in the [import guide](../scripts/python/README.md#import-metadata-and-vectors-directly-into-v2-postgresql).

```bash
python scripts/python/import_metadata_embeddings.py "$META" "$VECTORS" \
  --collection-slug my-collection --collection-name "My collection" \
  --report /path/to/my-collection-import.json
```

Use a new collection slug and report path. The importer writes each document and its texts once, creates or reuses an indexation per recipe, and commits the entire load in one transaction. Indexations use exact cosine search by default. `--ann hnsw_vector` builds an approximate index for supported dimensions.

Each recipe uses a global indexation slug. If an existing ready indexation has the same profile and configuration, the importer attaches the new collection and reuses its vector table. A conflict rolls back the import. Reused HNSW indexes must be ready. The report records whether each indexation was reused.

## 4. Verify the result

Keep the import report with both manifests. It records collection and indexation UUIDs, counts and source hashes. Check that the document count matches the export and that each indexation has one vector per document. The v2 API can retrieve a record by its `external_id`, which corresponds to the `record_key` in the SQLite index.

The imported profile uses `import_only` mode. To search, encode the query with the recipe's model revision and query template, then send the vector to v2 as `query.vector`. See the [v2 API workflows](WORKFLOWS.md) for search and retrieval examples.
