#!/usr/bin/env python3
"""Import a SQLite vector edition or paired JSONL packages into a new v2 collection."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import itertools
import json
import logging
import math
import os
import re
import sqlite3
import struct
import sys
import tempfile
import unicodedata
import uuid
from collections import Counter
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from compute_metadata_embeddings import file_entry, package_file, verify_file
from sqlite_vector_package import SQLiteVectorEdition

LOGGER = logging.getLogger("metadata_import")
SLUG = re.compile(r"[a-z0-9][a-z0-9_-]{0,99}")
TEXT_FIELDS = {"titles": "title", "descriptions": "description", "abstracts": "abstract", "keywords": "keyword"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def bad_constant(value):
    raise ValueError(f"Número JSON no finito: {value}")


def read_json(value):
    return json.loads(value, parse_constant=bad_constant)


def sha256(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def timestamp():
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class Part:
    source: Path
    vectors: Path
    locator: str
    count: int


@dataclass(frozen=True)
class IndexTarget:
    id: uuid.UUID
    table: str
    profile: uuid.UUID
    created: bool


def validate_recipes(recipes, ann):
    require(isinstance(recipes, list) and recipes, "El manifiesto debe definir al menos una receta")
    ids = set()
    for recipe in recipes:
        slug = recipe["id"]
        dims = recipe["dimensions"]
        require(isinstance(slug, str) and SLUG.fullmatch(slug) and slug not in ids,
                "Las recetas deben tener identificadores válidos y únicos")
        ids.add(slug)
        require(type(dims) is int and 1 <= dims <= (2000 if ann == "hnsw_vector" else 16000),
                f"Dimensión incompatible con la estrategia de índice para {slug}")
        require(recipe["outputDtype"] == "float32", f"Tipo de vector no soportado para {slug}")
        for key in ("model", "revision"):
            value = recipe[key]
            require(isinstance(value, str) and value.strip() and len(value) <= 255 and "\0" not in value,
                    f"Valor inválido para {key} de {slug}")
        template = recipe["input"]["queryTemplate"]
        require(isinstance(template, str) and template.endswith("{query}") and template.count("{query}") == 1
                and len(template.removesuffix("{query}")) <= 8192 and "\0" not in template,
                f"Plantilla de consulta no representable como prefijo fijo para {slug}")

def export_index_rows(index):
    return iter(index.execute(
        "SELECT file,position,network,catalog_id,identifier,metadata_hash,record_key,export_id,variant "
        "FROM records WHERE status='EXPORTED' ORDER BY file COLLATE BINARY,position"
    ))


def export_document(entry, locator, line, record, export_id, variant):
    identity = (locator, line, record["network"], record["catalogId"], record["identifier"], record["metadataHash"])
    require(entry is not None and entry[:6] == identity and entry[7:] == (export_id, variant),
            "Identidad o posición incompatible con records.sqlite")
    return prepare_document(entry[6], record)


def manifest_data(root):
    raw = package_file(root, "manifest.json").read_bytes()
    return read_json(raw), "sha256:" + hashlib.sha256(raw).hexdigest()


def listed_files(manifest):
    files = {entry["path"]: entry for entry in manifest["files"]}
    require(len(files) == len(manifest["files"]), "Duplicate paths in source manifest")
    return files


def export_part_names(files):
    return {name for name in files if name.endswith((".jsonl", ".jsonl.gz")) and name != "errors.jsonl"}


class Package:
    def __init__(self, source, embeddings, ann):
        source = source.resolve(strict=True)
        embeddings = embeddings.resolve(strict=True)
        original, source_hash = manifest_data(source)
        derived, vector_hash = manifest_data(embeddings)
        require(original.get("contractVersion") == 2 and original.get("status") == "COMPLETE"
                and original["options"]["format"] == "jsonl", "El export debe ser JSONL v2 y COMPLETE")
        require(type(derived.get("formatVersion")) is int and derived["formatVersion"] == 1
                and derived.get("type") == "metadata-embeddings" and derived.get("status") == "COMPLETE"
                and derived.get("format") == "jsonl" and derived.get("compression") == "gzip",
                "El paquete vectorial debe ser JSONL GZIP v1 y COMPLETE")
        self.export_id = original["exportId"]
        self.variant = original["options"]["variant"]
        self.report_source = {"exportId": self.export_id, "variant": self.variant,
                              "mappingHash": original.get("mappingHash"), "snapshots": original.get("sources", []),
                              "metadataManifestSha256": source_hash, "embeddingsManifestSha256": vector_hash}
        self.text_projection = {"fields": TEXT_FIELDS, "descriptionAbstractQualifier": "abstract",
                                "blankOccurrences": "preserved in metadata only"}
        require(derived["source"]["exportId"] == self.export_id
                and derived["source"]["manifestSha256"] == source_hash
                and derived["source"]["variant"] == self.variant
                and derived["source"].get("mappingHash") == original.get("mappingHash"),
                "Los manifiestos no corresponden al mismo export")
        self.count = original["counts"]["exported"]
        require(type(self.count) is int and self.count >= 0 and derived["counts"]["records"] == self.count,
                "Conteos incompatibles en los manifiestos")
        self.recipes = derived["embeddings"]
        validate_recipes(self.recipes, ann)
        files = listed_files(original)
        self.sqlite = verify_file(source, files["records.sqlite"])
        source_parts = export_part_names(files)
        inventory = derived["parts"]
        require({p["sourceFile"] for p in inventory} == source_parts and len(inventory) == len(source_parts)
                and len({p["file"] for p in inventory}) == len(inventory), "Correspondencia de partes incompleta o duplicada")
        self.parts = []
        for part in sorted(inventory, key=lambda item: item["sourceFile"]):
            locator = part["sourceFile"]
            source_entry = files[locator]
            count = part["records"]
            require(type(count) is int and count >= 0 and part["sourceSha256"] == source_entry["sha256"]
                    and part["file"].endswith(".jsonl.gz"), f"Parte incompatible: {locator}")
            self.parts.append(Part(verify_file(source, source_entry),
                                   verify_file(embeddings, {**part, "path": part["file"]}), locator, count))
        require(sum(part.count for part in self.parts) == self.count, "Conteos por parte incompatibles")

    def records(self):
        with closing(sqlite3.connect(self.sqlite.as_uri() + "?mode=ro&immutable=1", uri=True)) as index:
            rows = export_index_rows(index)
            for part in self.parts:
                opener = gzip.open if part.locator.endswith(".gz") else open
                count = 0
                with opener(part.source, "rt", encoding="utf-8") as docs, gzip.open(part.vectors, "rt", encoding="utf-8") as vecs:
                    for count, (doc, vec) in enumerate(itertools.zip_longest(docs, vecs), 1):
                        try:
                            require(doc is not None and vec is not None, "Distinto número de líneas")
                            record = read_json(doc)
                            result = export_document(next(rows, None), part.locator, count, record, self.export_id, self.variant)
                            vectors = prepare_vectors(read_json(vec), self.recipes)
                        except (ValueError, KeyError, TypeError, OverflowError) as exc:
                            raise ValueError(f"{part.locator}:{count}: {exc}") from exc
                        yield *result, vectors
                require(count == part.count, f"Conteo de líneas incorrecto: {part.locator}")
            require(next(rows, None) is None, "Sobran registros exportados en records.sqlite")



class SQLitePackage:
    def __init__(self, embeddings, ann, source=None):
        self.edition = SQLiteVectorEdition(embeddings)
        self.count, self.recipes = self.edition.count, self.edition.recipes
        validate_recipes(self.recipes, ann)
        self.codec = struct.Struct(f"<{self.recipes[0]['dimensions']}f")
        self.report_source = {
            "format": "sqlite",
            **self.edition.manifest["source"],
            "embeddingsManifestSha256": self.edition.manifest_hash,
            "vectorDatabaseSha256": self.edition.manifest["files"][0]["sha256"],
        }
        self.text_projection = {"mode": "identity-only"}
        self.source = source.resolve(strict=True) if source is not None else None
        if self.source is not None:
            self.bind_source()

    def bind_source(self):
        original, digest = manifest_data(self.source)
        provenance = self.edition.input
        require(original.get("status") == "COMPLETE" and digest == provenance["manifestSha256"],
                "Source manifest does not match SQLite vector provenance")
        files = listed_files(original)
        self.prepared = "packageId" in provenance
        if self.prepared:
            require(original.get("packageType") == "embedding-inputs" and original.get("contractVersion") == 1
                    and original.get("packageId") == provenance["packageId"], "Expected the prepared embedding-inputs v1 package")
            parts = original["parts"]
            count = original["counts"]["emitted"]
            self.text_projection = {"mode": "prepared-text", "field": "text", "kind": "body"}
        else:
            require(original.get("contractVersion") == 2 and original["options"]["format"] == "jsonl"
                    and original.get("exportId") == provenance["exportId"]
                    and original["options"]["variant"] == provenance["variant"]
                    and original.get("mappingHash") == provenance.get("mappingHash"),
                    "Expected the original JSONL v2 export")
            parts = sorted(export_part_names(files))
            count = original["counts"]["exported"]
            self.index = verify_file(self.source, files["records.sqlite"])
            self.text_projection = {"fields": TEXT_FIELDS, "descriptionAbstractQualifier": "abstract",
                                    "blankOccurrences": "preserved in metadata only"}
        require(count == self.count and parts == [part["sourceFile"] for part in self.edition.parts],
                "Source inventory or count does not match SQLite vectors")
        self.source_parts = []
        for part in self.edition.parts:
            name = part["sourceFile"]
            entry = files[name]
            require(entry["sha256"] == part["sourceSha256"] and entry["bytes"] == part.get("sourceBytes", entry["bytes"]),
                    f"Source part does not match SQLite vectors: {name}")
            self.source_parts.append((part, verify_file(self.source, entry)))
        self.report_source["metadataManifestSha256"] = digest

    def documents(self):
        index = None
        try:
            if not self.prepared:
                index = sqlite3.connect(self.index.as_uri() + "?mode=ro&immutable=1", uri=True)
                rows = export_index_rows(index)
            for part, path in self.source_parts:
                name = part["sourceFile"]
                opener = gzip.open if name.endswith(".gz") else open
                count = 0
                with opener(path, "rt", encoding="utf-8") as stream:
                    for count, raw in enumerate(stream, 1):
                        doc = read_json(raw)
                        identity = (doc["network"], doc["catalogId"])
                        if self.prepared:
                            external, metadata, _, _ = catalog_identity(*identity)
                            text = unicode_text(doc["text"], 2_000_000)
                            require(not blank(text), f"Blank prepared text: {name}:{count}")
                            document = external, metadata, [("import:prepared", "body", None, text, sha256(text))], 0
                        else:
                            document = export_document(next(rows, None), name, count, doc,
                                                       self.edition.input["exportId"], self.edition.input["variant"])
                        yield (name, count, *identity), document
                require(count == part["records"], f"Source row count mismatch: {name}")
            if index is not None:
                require(next(rows, None) is None, "Extra exported rows in records.sqlite")
        finally:
            if index is not None:
                index.close()

    def records(self):
        with closing(self.edition.records()) as vectors:
            if self.source is None:
                for locator, payload in vectors:
                    yield *catalog_identity(*locator[2:]), self.decode(locator, payload)
            else:
                with closing(self.documents()) as documents:
                    for vector, document in itertools.zip_longest(vectors, documents):
                        require(vector is not None and document is not None and vector[0] == document[0],
                                "Source row identity or position does not match SQLite vectors")
                        yield *document[1], self.decode(*vector)

    def decode(self, locator, payload):
        part, line, *_ = locator
        require(len(payload) == self.codec.size, f"Wrong float32 vector size: {part}:{line}")
        values = self.codec.unpack(payload)
        require(all(map(math.isfinite, values)) and any(values), f"Non-finite or zero vector: {part}:{line}")
        return [json.dumps(values, allow_nan=False, separators=(",", ":"))]


def catalog_identity(network, catalog_id):
    require(unicode_text(network).strip() and unicode_text(catalog_id).strip(), "Blank network or catalog ID")
    external_id = unicode_text(f"{network}_{catalog_id}", 512)
    return external_id, {"network": network, "catalogId": catalog_id}, [], 0


def open_package(source, embeddings, ann):
    if embeddings is None:
        return SQLitePackage(source, ann)
    manifest, _ = manifest_data(embeddings.resolve(strict=True))
    if manifest.get("format") == "sqlite":
        return SQLitePackage(embeddings, ann, source=source)
    return Package(source, embeddings, ann)


def unicode_text(value, limit=None):
    require(isinstance(value, str) and "\0" not in value and (limit is None or len(value) <= limit),
            "Cadena inválida o demasiado larga")
    value.encode("utf-8")
    return value


def blank(content):
    # Java Character.isWhitespace() or isSpaceChar(), as used by v2.
    return all(char in "\t\n\v\f\r\x1c\x1d\x1e\x1f" or unicodedata.category(char) in {"Zs", "Zl", "Zp"} for char in content)


def prepare_document(external_id, record):
    require(bool(unicode_text(external_id, 512)), "external_id vacío")
    data = record["data"]
    require(isinstance(data, dict), "data debe ser un objeto")
    metadata = {key: record[key] for key in ("network", "catalogId", "identifier", "metadataHash", "data")}
    require(len(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= 256 * 1024,
            "Metadata supera el límite por defecto de v2")
    ordinals = Counter()
    texts = []
    skipped = 0
    for field, default_kind in TEXT_FIELDS.items():
        occurrences = data.get(field, [])
        require(isinstance(occurrences, list), f"data.{field} debe ser un array")
        for occurrence in occurrences:
            kind = default_kind
            language = None
            if isinstance(occurrence, dict):
                content = occurrence.get("value")
                language = occurrence.get("language")
                qualifier = occurrence.get("qualifier", "")
                require(isinstance(qualifier, str), "Qualifier inválido")
                if field == "descriptions" and qualifier.strip().split(".", 1)[0].casefold() == "abstract":
                    kind = "abstract"
            else:
                content = occurrence
            content = unicode_text(content, 2_000_000)
            if language is not None:
                unicode_text(language, 35)
            if blank(content):
                skipped += 1
                continue
            group = kind, language
            key = "import:" + sha256(kind + "\0" + (language if language is not None else "\0")) + ":" + str(ordinals[group])
            ordinals[group] += 1
            texts.append((key, kind, language, content, sha256(content)))
    require(len(texts) <= 1000, "Demasiados textos para un documento")
    snapshot = {"metadata": metadata, "texts": [{"knd": kind, "lng": lang, "cnt": content} for _, kind, lang, content, _ in texts]}
    require(len(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= 16 * 1024 * 1024,
            "Documento y textos superan el límite por defecto de v2")
    return external_id, metadata, texts, skipped


def prepare_vectors(vectors, recipes):
    require(isinstance(vectors, list) and len(vectors) == len(recipes), "Número de posiciones inesperado")
    encoded = []
    for vector, recipe in zip(vectors, recipes, strict=True):
        require(isinstance(vector, list) and len(vector) == recipe["dimensions"], f"Dimensión incorrecta: {recipe['id']}")
        require(all(type(value) in (float, int) and math.isfinite(value) for value in vector), "Componente inválido")
        fmt = f"={len(vector)}f"
        values = struct.unpack(fmt, struct.pack(fmt, *vector))
        require(all(math.isfinite(value) for value in values) and any(values), "Vector float32 no finito o cero para coseno")
        encoded.append(json.dumps(vector, allow_nan=False, separators=(",", ":")))
    return encoded


def configuration(recipe):
    return {
        "model": recipe["model"], "model_revision": recipe["revision"], "dimensions": recipe["dimensions"],
        "execution_mode": "import_only", "provider_ref": None,
        "preparation": {"version": "identity-v1", "document_prefix": "",
                        "query_prefix": recipe["input"]["queryTemplate"].removesuffix("{query}")},
    }


def create_index(conn, sql, Jsonb, canonicalize, collection, recipe, build_ann):
    profile = uuid.uuid4()
    config = configuration(recipe)
    fingerprint = hashlib.sha256(canonicalize(config)).hexdigest()
    conn.execute("""
        INSERT INTO dvp.embedding_profiles
            (id,slug,model,model_revision,dimensions,execution_mode,provider_ref,preparation,fingerprint)
        VALUES (%s,%s,%s,%s,%s,'import_only',NULL,%s,%s) ON CONFLICT (fingerprint) DO NOTHING
    """, (profile, "import-" + fingerprint, config["model"], config["model_revision"], config["dimensions"],
          Jsonb(config["preparation"]), fingerprint))
    found = conn.execute("""
        SELECT id,model,model_revision,dimensions,execution_mode,provider_ref,preparation
        FROM dvp.embedding_profiles WHERE fingerprint=%s
    """, (fingerprint,)).fetchone()
    require(found is not None and found[1:] == tuple(config.values()), "Perfil existente incompatible")
    profile = found[0]
    strategy = "hnsw_vector" if build_ann else "none"
    selection = {"kinds": [], "languages": []}
    chunking = {"mode": "none"}
    # Serialize resolution with the Java API and other import processes.
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", ("dvp:indexation:" + recipe["id"],))
    existing = conn.execute("""
        SELECT id,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state
        FROM dvp.indexations WHERE slug=%s
    """, (recipe["id"],)).fetchone()
    if existing is not None:
        index, existing_profile, existing_selection, existing_chunking, metric, existing_strategy, table, state, ann_state = existing
        require(existing_profile == profile and existing_selection == selection and existing_chunking == chunking
                and metric == "cosine" and existing_strategy == strategy,
                f"La indexación {recipe['id']} existe con una configuración o perfil incompatible")
        require(state == "ready" and (not build_ann or ann_state == "ready"),
                f"La indexación {recipe['id']} o su índice ANN no está listo")
        require(table == "vec_" + index.hex and conn.execute("SELECT to_regclass(%s)", ("dvp." + table,)).fetchone()[0] is not None,
                f"La tabla de vectores de {recipe['id']} no está disponible")
        conn.execute("INSERT INTO dvp.collection_indexations (indexation_id,collection_id) VALUES (%s,%s)", (index, collection))
        return IndexTarget(index, table, profile, False)

    index = uuid.uuid4()
    table = "vec_" + index.hex
    conn.execute("""
        INSERT INTO dvp.indexations
            (id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (%s,%s,%s,%s,%s,'cosine',%s,%s,'provisioning',%s)
    """, (index, recipe["id"], profile, Jsonb(selection), Jsonb(chunking), strategy, table, "pending" if build_ann else "disabled"))
    conn.execute("INSERT INTO dvp.collection_indexations (indexation_id,collection_id) VALUES (%s,%s)", (index, collection))
    conn.execute(sql.SQL("""
        CREATE TABLE dvp.{} (
          unit_id uuid PRIMARY KEY, document_id uuid NOT NULL,
          indexation_id uuid NOT NULL CHECK (indexation_id={}::uuid),
          embedding vector({}) NOT NULL, input_hash char(64),
          origin varchar(10) NOT NULL CHECK (origin IN ('generated','imported','reused')),
          origin_job_id uuid, created_at timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT {} FOREIGN KEY (unit_id,document_id,indexation_id)
            REFERENCES dvp.vector_units(id,document_id,indexation_id) ON DELETE CASCADE
        )
    """).format(sql.Identifier(table), sql.Literal(index), sql.Literal(config["dimensions"]),
                sql.Identifier(table + "_unit_scope_fk")))
    return IndexTarget(index, table, profile, True)


def copy_rows(conn, query, rows):
    with conn.cursor().copy(query) as copy:
        for row in rows:
            copy.write_row(row)


def copy_batch(conn, sql, Jsonb, collection, recipes, indexes, batch):
    documents, texts, units = [], [], []
    vector_rows = [[] for _ in recipes]
    for external_id, metadata, sources, _, vectors in batch:
        document_id = uuid.uuid4()
        documents.append((document_id, collection, external_id, Jsonb(metadata)))
        texts.extend((uuid.uuid4(), document_id, *source) for source in sources)
        for position, (recipe, target, vector) in enumerate(zip(recipes, indexes, vectors, strict=True)):
            unit_id = uuid.uuid4()
            units.append((unit_id, collection, target.id, document_id, 0, recipe["id"]))
            vector_rows[position].append((unit_id, document_id, target.id, vector, "imported"))
    copy_rows(conn, "COPY dvp.documents (id,collection_id,external_id,metadata) FROM STDIN", documents)
    copy_rows(conn, "COPY dvp.source_texts (id,document_id,text_key,kind,language,content,content_hash) FROM STDIN", texts)
    copy_rows(conn, "COPY dvp.vector_units (id,collection_id,indexation_id,document_id,ordinal,embedding_key) FROM STDIN", units)
    for target, rows in zip(indexes, vector_rows, strict=True):
        query = sql.SQL("COPY dvp.{} (unit_id,document_id,indexation_id,embedding,origin) FROM STDIN").format(sql.Identifier(target.table))
        copy_rows(conn, query, rows)


def count_records(package, write_batch=None, batch_size=256):
    counts = {"documents": 0, "texts": 0, "blankTextsSkipped": 0, "vectorsPerRecipe": 0}
    batch = []
    with closing(package.records()) as records:
        for record in records:
            counts["documents"] += 1
            counts["texts"] += len(record[2])
            counts["blankTextsSkipped"] += record[3]
            if write_batch is not None:
                batch.append(record)
                if len(batch) == batch_size:
                    write_batch(batch)
                    batch.clear()
            if counts["documents"] % 10000 == 0:
                LOGGER.info("%d documentos procesados", counts["documents"])
        if batch:
            write_batch(batch)
    require(counts["documents"] == package.count, "Conteo total de documentos incorrecto")
    counts["vectorsPerRecipe"] = counts["documents"]
    return counts


def finish_indexes(conn, sql, collection, indexes, counts, build_ann):
    docs, texts = conn.execute("""
        SELECT (SELECT count(*) FROM dvp.documents WHERE collection_id=%s),
               (SELECT count(*) FROM dvp.source_texts s JOIN dvp.documents d ON d.id=s.document_id WHERE d.collection_id=%s)
    """, (collection, collection)).fetchone()
    require((docs, texts) == (counts["documents"], counts["texts"]), "Conteos SQL de documentos o textos incorrectos")
    for target in indexes:
        index, table = target.id, target.table
        relation = sql.Identifier("dvp", table)
        vectors = conn.execute(sql.SQL("SELECT count(*) FROM {} v JOIN dvp.vector_units u ON u.id=v.unit_id AND u.document_id=v.document_id AND u.indexation_id=v.indexation_id WHERE u.collection_id=%s AND u.indexation_id=%s").format(relation), (collection, index)).fetchone()[0]
        units = conn.execute("SELECT count(*) FROM dvp.vector_units WHERE collection_id=%s AND indexation_id=%s",
                             (collection, index)).fetchone()[0]
        require(vectors == units == counts["vectorsPerRecipe"], "Conteo SQL de vectores o unidades incorrecto")
        if target.created:
            for suffix, columns in (("_doc", "document_id,unit_id"), ("_hash", "input_hash")):
                conn.execute(sql.SQL("CREATE INDEX {} ON {} ({})").format(
                    sql.Identifier(table + suffix), relation, sql.SQL(columns)))
            if build_ann:
                conn.execute(sql.SQL("CREATE INDEX {} ON {} USING hnsw (embedding vector_cosine_ops)").format(
                    sql.Identifier(table + "_ann"), relation))
        conn.execute(sql.SQL("ANALYZE {}").format(relation))
        if target.created:
            conn.execute("UPDATE dvp.indexations SET state='ready',ann_state=%s WHERE id=%s",
                         ("ready" if build_ann else "disabled", index))


def apply(args, package, report):
    import psycopg
    import rfc8785
    from psycopg import sql
    from psycopg.types.json import Jsonb

    dsn = os.environ.get(args.dsn_env)
    require(dsn, f"Configura la conexión en la variable {args.dsn_env}")
    output = args.report
    require(not output.exists() and output.parent.is_dir(), "El informe debe ser nuevo y su carpeta debe existir")
    collection = uuid.uuid4()
    temporary = None
    committed = False
    report["collection"]["id"] = str(collection)
    try:
        with psycopg.connect(dsn) as conn:
            require(conn.execute("SELECT 1 FROM pg_extension WHERE extname='vector'").fetchone() is not None,
                    "Falta la extensión vector")
            conn.execute("INSERT INTO dvp.collections (id,slug,name) VALUES (%s,%s,%s)",
                         (collection, args.collection_slug, args.collection_name))
            build_ann = args.ann == "hnsw_vector"
            indexes = [create_index(conn, sql, Jsonb, rfc8785.dumps, collection, recipe, build_ann) for recipe in package.recipes]
            write = lambda batch: copy_batch(conn, sql, Jsonb, collection, package.recipes, indexes, batch)
            report["counts"] = count_records(package, write, args.batch_size)
            finish_indexes(conn, sql, collection, indexes, report["counts"], build_ann)
            for position, (item, target) in enumerate(zip(report["indexations"], indexes, strict=True)):
                item.update(id=str(target.id), profileId=str(target.profile), vectorTable=f"dvp.{target.table}", position=position, reused=not target.created)
            report["status"] = "COMPLETE"
            report["finishedAt"] = timestamp()
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix=f".{output.name}.",
                                             dir=output.parent, delete=False) as stream:
                temporary = Path(stream.name)
                json.dump(report, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
        committed = True
        os.link(temporary, output)
    except psycopg.errors.UniqueViolation as exc:
        raise ValueError("La colección ya existe o hay claves duplicadas en el destino") from exc
    except psycopg.Error as exc:
        raise ValueError(f"PostgreSQL rechazó la carga ({exc.sqlstate or type(exc).__name__})") from exc
    except OSError as exc:
        if committed:
            raise ValueError(f"La colección {collection} se confirmó. Recupera el informe de {temporary}") from exc
        raise
    finally:
        if temporary is not None and (not committed or output.exists() and os.path.samefile(temporary, output)):
            temporary.unlink()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="SQLite vector edition, or the source package when supplying two directories")
    parser.add_argument("embeddings", type=Path, nargs="?", help="Vector package when providing its source separately")
    parser.add_argument("--collection-slug", required=True)
    parser.add_argument("--collection-name", required=True)
    parser.add_argument("--ann", choices=("none", "hnsw_vector"), default="none")
    parser.add_argument("--batch-size", type=int, default=256, help="Documentos por lote COPY (1–1000)")
    parser.add_argument("--dsn-env", default="DATABASE_URL", help="Variable que contiene el DSN de PostgreSQL")
    parser.add_argument("--dry-run", action="store_true", help="Valida los paquetes sin abrir PostgreSQL")
    parser.add_argument("--report", type=Path, help="Ruta nueva del informe; obligatoria para cargar")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    try:
        require(SLUG.fullmatch(args.collection_slug), "Slug de colección inválido")
        require(unicode_text(args.collection_name, 200).strip(), "Nombre de colección vacío")
        require(1 <= args.batch_size <= 1000, "batch-size debe estar entre 1 y 1000")
        require(args.dry_run or args.report is not None, "--report es obligatorio para cargar")
        package = open_package(args.source, args.embeddings, args.ann)
        report = {
            "formatVersion": 1, "startedAt": timestamp(),
            "collection": {"slug": args.collection_slug, "name": args.collection_name},
            "source": package.report_source,
            "indexations": [{"slug": recipe["id"], "metric": "cosine", "chunking": "none",
                             "annStrategy": args.ann, "recipe": recipe} for recipe in package.recipes],
            "textProjection": package.text_projection,
            "batchSize": args.batch_size,
        }
        if args.dry_run:
            report["counts"] = count_records(package)
            report["status"] = "VALIDATED"
            report["finishedAt"] = timestamp()
            if args.report:
                with args.report.open("x", encoding="utf-8") as stream:
                    json.dump(report, stream, ensure_ascii=False, indent=2)
                    stream.write("\n")
        else:
            apply(args, package, report)
        print(json.dumps({"status": report["status"], "collection": report["collection"], "counts": report["counts"]}, ensure_ascii=False))
        return 0
    except (ValueError, OSError, KeyError, TypeError, ImportError, sqlite3.Error, struct.error, EOFError, OverflowError) as exc:
        LOGGER.error("%s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
