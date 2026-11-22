# Python scripts

## Calculate vectors from prepared texts

The shell's `prepare-embedding-metadata --combine` command produces an
`embedding-inputs` v1 package with one text per record. Run the same batch
calculator used for harvester exports.

```sh
python scripts/python/compute_metadata_embeddings.py "$PREPARED_DIR" "$VECTOR_DIR" \
  --revision "$MODEL_REVISION"
```

The calculator reads the manifest's ordered `parts`, verifies their sizes and
SHA-256 checksums and sends each row's `text` to the encoder. The shell has
already applied the model's document template. When the shell includes a
provenance sidecar, it remains available for auditing in the input package.

This mode emits an `embedding-vectors` v1 package. Each GZIP JSONL row contains
`network`, `catalogId` and a numeric `vector`. The vector manifest lists the
corresponding input part and output part, the model revision and dimensions,
and the number of rows. The default overflow policy truncates the encoder input
and reports the input part, line, original token count and final token count.
Set `--on-overflow error` to reject long inputs. The prepared package retains the
complete text. The PostgreSQL import recipe below applies to vectors calculated
from the original harvester export.


See the [harvester-to-v2 guide](../../docs/HARVESTER-EMBEDDINGS.md) for the full workflow.

## Calculate embeddings from a harvester export

`compute_metadata_embeddings.py` reads a `COMPLETE` JSONL export and generates a separate vector package with EmbeddingGemma 2.

Use Python 3.11 or later with PyTorch, NumPy, Hugging Face Hub, Transformers and Sentence Transformers. The UY test used Python 3.12, PyTorch 2.11.0+cu128, Transformers 5.23.0 and Sentence Transformers 6.3.0. For CUDA, choose a PyTorch build compatible with the GPU and driver.

```bash
python scripts/python/compute_metadata_embeddings.py \
  /path/to/harvester-export \
  /path/to/emb-uy-title-abstract \
  --revision f9972ac22e696a105c482202646979e33e4b5a1e \
  --offline
```

`--revision` pins the exact model commit. With `--offline`, the revision must be in the local cache. CUDA and BF16 are the defaults. For CPU, use `--device cpu --dtype float32`.

The recipe takes the first nonblank `data.titles` value and joins nonblank `data.descriptions` occurrences whose first qualifier component is `abstract`, in source order. If there is no abstract, it uses the title as the body. The model receives `title: {title} | text: {text}` and returns a 768-dimensional vector normalized in FP32. The input limit is 8,192 tokens. The calculator rejects longer inputs by default. Set `--on-overflow truncate` to truncate only oversized inputs. The manifest records their files, lines and token counts before and after truncation in `truncatedInputs`. The source export retains the full text. For a dedicated abstract field, use `--abstract-field abstracts --abstract-qualifier ''`. To use every description, pass `--abstract-qualifier '' --recipe-id title-descriptions`.

### Output package

```text
emb-uy-title-abstract/
  manifest.json
  UY/part-000001.embeddings.jsonl.gz
  UY/part-000002.embeddings.jsonl.gz
```

Each line contains an array of vectors. Vector positions correspond to the positions in `embeddings` in the manifest. This script writes a single position, `title-abstract`.

```json
[[0.0312, -0.0187, 0.0048]]
```

The example shows an abbreviated vector. Line 42 of a vector part corresponds to line 42 of the metadata part listed in `parts[].sourceFile`. The manifest records the export identity, model and revision, parameters, recipe, counts, and source and output SHA-256 checksums.

The script opens `records.sqlite` in read-only mode and checks each record's identity, metadata hash and position. The index stays in the original package. Choose a new destination. The calculator publishes it after all parts finish. If an error interrupts the run, rerun it from the beginning.

To use the v2 import API, combine the metadata and vector parts into the objects the API accepts. See the [streaming import guide](../../docs/PLATFORM-GUIDE.md#9-streaming-imports).

## Import a SQLite vector edition into v2

Use the maintained calculator from `lareferencia-metadata-tools` with
`--format sqlite`, or convert a combined GZIP JSONL vector package with
`enrichments pack-vectors`. Both commands produce a directory containing
`embeddings.sqlite` and `manifest.json`. The
[SQLite interchange contract](https://github.com/LA-Referencia-IOI/lareferencia-metadata-tools/blob/97921d42f9677ba22ac34cc4bfd3110633722fd1/docs/METADATA_ENRICHMENT_PACKAGE.md#pack-calculated-embeddings-in-sqlite)
describes the schema and the two sets of source references.

Pass the edition directory to the v2 importer. The dry run opens SQLite in
read-only mode and checks the manifest, database checksum, schema version,
locators, dimensions and counts. It decodes the little-endian float32 vectors
and validates each for cosine search. It needs neither PostgreSQL nor the
metadata-tools or model dependencies.

```sh
python scripts/python/import_metadata_embeddings.py "$SQLITE_EDITION_DIR" \
  --collection-slug ar-combined --collection-name "Argentina · Combined" --dry-run
```

For the actual load, use Python 3.11+ and install the requirements below.
Set `DATABASE_URL` to a PostgreSQL database with pgvector and the migrated
v2 schema. Use a new collection slug and report path.

```sh
python -m pip install -r scripts/python/requirements-import.txt

python scripts/python/import_metadata_embeddings.py "$SQLITE_EDITION_DIR" \
  --collection-slug ar-combined --collection-name "Argentina · Combined" \
  --report /path/to/ar-combined-import.json
```

The SQLite-only import creates one document and one vector per item. Document
metadata contains `network` and `catalogId`, and the external ID follows
`network_catalogId`. To store the exact prepared text as a `body` source text,
pass the original `embedding-inputs` package before the edition directory.

```sh
python scripts/python/import_metadata_embeddings.py "$PREPARED_DIR" "$SQLITE_EDITION_DIR" \
  --collection-slug ar-combined --collection-name "Argentina · Combined" --dry-run
```

The same two-argument form accepts the original harvester JSONL v2 export for
a SQLite edition calculated directly from that export. It keeps the metadata
and source-text projection described below. In either paired mode, the
importer verifies the source manifest hash and checks each part, line and
identity against the SQLite edition. The PostgreSQL load applies the same
transaction, compatible indexation reuse and reporting as the JSONL import.

## Import metadata and vectors directly into v2 PostgreSQL

`import_metadata_embeddings.py` creates a new collection from the original JSONL export and its separate vector package. It requires Python 3.11+, PostgreSQL with pgvector and the `dvp` schema migrated by the v2 service. The database role needs permission to read and insert into v2 tables, update indexation state, access the sequences and create tables and indexes in `dvp`. Install the extension and apply migrations before importing.

To reuse an indexation created by another database role, the importer also
needs `INSERT` privileges on its existing `dvp.vec_*` table and the appropriate
privileges to analyze it. Use the current v2 schema initialized by the service.

```bash
python -m pip install -r scripts/python/requirements-import.txt

python scripts/python/import_metadata_embeddings.py \
  /path/to/harvester-export /path/to/emb-uy-title-abstract \
  --collection-slug uy-gemma2 --collection-name "UY · EmbeddingGemma 2" \
  --dry-run

# Set DATABASE_URL in the environment to the PostgreSQL DSN.
python scripts/python/import_metadata_embeddings.py \
  /path/to/harvester-export /path/to/emb-uy-title-abstract \
  --collection-slug uy-gemma2 --collection-name "UY · EmbeddingGemma 2" \
  --report /path/to/uy-gemma2-import.json
```

| Parameter | Behavior |
|---|---|
| `source` | Directory of the `COMPLETE` v2 JSONL export with `manifest.json` and `records.sqlite`. |
| `embeddings` | Directory of the `COMPLETE` v1 `metadata-embeddings` package with GZIP JSONL parts. |
| `--collection-slug` | New identifier of up to 100 characters using lowercase letters, digits, hyphens or underscores. |
| `--collection-name` | Display name of up to 200 characters. |
| `--ann` | Defaults to `none` for exact search. `hnsw_vector` builds a cosine HNSW index after loading and supports up to 2,000 dimensions. |
| `--batch-size` | Documents per COPY batch, from 1 to 1,000. Defaults to 256. All batches run in a single transaction. |
| `--dsn-env` | Connection environment variable. Defaults to `DATABASE_URL`. |
| `--dry-run` | Verifies manifests, hashes, alignment, texts and dimensions without opening PostgreSQL. |
| `--report` | New JSON report path, required for loading. Its parent directory must exist. |

### Relational model and provenance

- Each document and its texts are written once. Its `external_id` is the SQLite `record_key`. Document metadata preserves `network`, `catalogId`, `identifier`, `metadataHash` and the entire export `data` object.
- The importer projects `titles`, `descriptions`, `abstracts` and `keywords` into `source_texts` as `title`, `description`, `abstract` and `keyword`. A description whose first qualifier component is `abstract` is stored as an abstract. The importer preserves explicit languages. Blank occurrences stay in `data`. It writes the other occurrences as supplied, with the keys and hashes expected by v2.
- Each embedding recipe resolves a global indexation by its recipe ID. The
  importer creates it when absent or attaches the new collection to an existing
  ready indexation with the same profile, selection, chunking, cosine metric,
  and ANN strategy. A conflicting configuration fails and rolls back the whole
  import. HNSW indexations must already have a ready ANN index to be reused.
  Each document has one unit per recipe with that recipe ID as `embedding_key`.
  The `import_only` profile is reused by its v2 fingerprint; search accepts a
  compatible query vector or a text query with a compatible HTTP query profile.
- The report records the collection UUID, profile, indexation and settings for each recipe, manifest hashes, export ID, variant, snapshots and counts. Keep it with both manifests. Shared provenance is recorded once in the report.

The report's `reused` field identifies indexations already present before this
import. Existing vector tables and ANN indexes are retained; imported-vector
counts are checked for the new collection. Test the importer only against a
disposable PostgreSQL server using `DVP_PYTHON_TEST_DSN` and
`python -m unittest discover -s scripts/python/tests -v`.

The importer checks `records.sqlite` in read-only mode. A mismatch, invalid vector or PostgreSQL error rolls back the collection and any tables created in that transaction. The importer rejects an existing slug. It publishes the report after commit. If publication fails after commit, the script prints the temporary report path for recovery.
