#!/usr/bin/env python3
"""Compute embeddings for a metadata export and write an aligned JSONL vector package."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import logging
import re
import shutil
import sqlite3
import tempfile
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

LOGGER = logging.getLogger("metadata_embeddings")
MODEL_ID = "google/embeddinggemma-2"
DOCUMENT_TEMPLATE = "title: {title} | text: {text}"
QUERY_TEMPLATE = "task: search result | query: {query}"
DIMENSIONS = 768


def file_entry(path):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return {"bytes": stream.tell(), "sha256": f"sha256:{digest}"}


def package_file(root, relative):
    name = PurePosixPath(relative)
    if name.is_absolute() or not name.parts or ".." in name.parts:
        raise ValueError(f"Ruta inválida en el manifiesto: {relative}")
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError(f"Archivo fuera del paquete o no regular: {relative}")
    return path


def verify_file(root, entry):
    path = package_file(root, entry["path"])
    if file_entry(path) != {key: entry[key] for key in ("bytes", "sha256")}:
        raise ValueError(f"Tamaño o SHA-256 incorrecto: {entry['path']}")
    return path


def input_text(record, args):
    data = record["data"]
    titles = data.get(args.title_field, [])
    abstracts = data.get(args.abstract_field, [])
    if not isinstance(titles, list) or not isinstance(abstracts, list):
        raise ValueError("Los campos de título y abstract deben ser arrays")

    def values(items, qualifier=None):
        for item in items:
            if isinstance(item, str):
                value, root = item, ""
            elif isinstance(item, dict) and isinstance(item.get("value"), str):
                value = item["value"]
                root = str(item.get("qualifier", "")).strip().split(".", 1)[0].casefold()
            else:
                raise ValueError("Ocurrencia sin valor textual")
            if value.strip() and (qualifier is None or root == qualifier):
                yield value.strip()

    title = next(values(titles), "")
    abstract = "\n\n".join(values(abstracts, args.abstract_qualifier))
    if not title and not abstract:
        raise ValueError("Registro sin título ni abstract utilizable")
    return DOCUMENT_TEMPLATE.format(title=title or "none", text=abstract or title), bool(abstract)


class Encoder:
    def __init__(self, args):
        import numpy as np
        import sentence_transformers
        import torch
        import transformers
        from huggingface_hub import snapshot_download
        from sentence_transformers import SentenceTransformer

        self.np = np
        self.args = args
        if args.device.startswith("cuda") and not torch.cuda.is_available():
            raise ValueError("CUDA no disponible")
        if args.dtype == "bfloat16":
            if not args.device.startswith("cuda"):
                raise ValueError("BF16 requiere un dispositivo CUDA en este CLI")
            with torch.cuda.device(args.device):
                if not torch.cuda.is_bf16_supported():
                    raise ValueError("BF16 no está disponible en el dispositivo elegido")
        snapshot = snapshot_download(MODEL_ID, revision=args.revision, local_files_only=args.offline)
        self.model = SentenceTransformer(
            snapshot, device=args.device, local_files_only=True,
            model_kwargs={"torch_dtype": getattr(torch, args.dtype)},
            config_kwargs={"vision_config": None, "audio_config": None},
        )
        self.model.max_seq_length = args.max_tokens
        self.model.eval()
        if self.model.get_embedding_dimension() != DIMENSIONS:
            raise ValueError("Dimensión inesperada del modelo")
        if self.model.prompts.get("Retrieval-query") != QUERY_TEMPLATE.removesuffix("{query}"):
            raise ValueError("Prompt de consulta inesperado en la revisión del modelo")
        if next(self.model.parameters()).dtype != getattr(torch, args.dtype):
            raise ValueError("El modelo no se cargó con la precisión solicitada")
        self.tokenizer = self.model.tokenizer
        self.versions = {
            "sentenceTransformers": sentence_transformers.__version__,
            "transformers": transformers.__version__,
            "torch": torch.__version__,
        }

    def encode(self, texts):
        np = self.np
        lengths = self.tokenizer(texts, truncation=False, padding=False, add_special_tokens=True)["input_ids"]
        counts = [len(ids) for ids in lengths]
        for index, count in enumerate(counts):
            if count > self.args.max_tokens:
                raise ValueError(f"Entrada {index + 1} del lote supera {self.args.max_tokens} tokens ({count})")
        order = sorted(range(len(texts)), key=counts.__getitem__)
        vectors = np.empty((len(texts), DIMENSIONS), dtype=np.float32)
        start = 0
        while start < len(order):
            length = counts[order[start]]
            # Keep padding and activation memory bounded for long inputs.
            upper = next(limit for limit in (512, 1024, 2048, 8192) if length <= limit)
            size = min(self.args.batch_size, {512: 16, 1024: 8, 2048: 4, 8192: 1}[upper])
            end = start + 1
            while end < min(start + size, len(order)) and counts[order[end]] <= upper:
                end += 1
            indices = order[start:end]
            raw = self.model.encode(
                [texts[index] for index in indices], prompt="", batch_size=len(indices),
                normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False,
            )
            batch = np.asarray(raw, dtype=np.float32)
            if batch.shape != (len(indices), DIMENSIONS) or not np.isfinite(batch).all():
                raise ValueError("El modelo devolvió vectores inválidos")
            norms = np.linalg.norm(batch, axis=1, keepdims=True)
            if np.any(norms <= 0) or not np.isfinite(norms).all():
                raise ValueError("El modelo devolvió una norma inválida")
            vectors[indices] = batch / norms
            start = end
        return vectors


def write_batch(stream, encoder, texts, source_file, first_line):
    try:
        vectors = encoder.encode(texts)
    except (RuntimeError, ValueError) as exc:
        raise ValueError(f"{source_file}, líneas {first_line}–{first_line + len(texts) - 1}: {exc}") from exc
    for vector in vectors:
        values = (format(value, ".9g") for value in vector.tolist())
        # Nine significant digits round-trip float32. Preserve signed zero too.
        stream.write("[[" + ",".join("-0.0" if value == "-0" else value for value in values) + "]]\n")


def write_part(source_path, source_file, destination, index_rows, encoder, args):
    count = with_abstract = 0
    texts = []
    read = gzip.open if source_file.endswith(".gz") else open
    destination.parent.mkdir(parents=True, exist_ok=True)
    with read(source_path, "rt", encoding="utf-8") as source, gzip.open(destination, "wt", encoding="utf-8", compresslevel=3) as output:
        for count, line in enumerate(source, 1):
            try:
                record = json.loads(line)
                identity = (source_file, count, record["network"], record["catalogId"], record["identifier"], record["metadataHash"])
                if next(index_rows, None) != identity:
                    raise ValueError("La posición, identidad o hash no coincide con records.sqlite")
                text, has_abstract = input_text(record, args)
            except (ValueError, KeyError, TypeError) as exc:
                raise ValueError(f"{source_file}:{count}: {exc}") from exc
            with_abstract += has_abstract
            texts.append(text)
            if len(texts) == args.chunk_size:
                write_batch(output, encoder, texts, source_file, count - len(texts) + 1)
                texts.clear()
        if texts:
            write_batch(output, encoder, texts, source_file, count - len(texts) + 1)
    return count, with_abstract


def build(args):
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    if output.exists() or output.is_relative_to(source):
        raise ValueError("La salida debe ser una carpeta nueva fuera del paquete de origen")
    manifest_path = package_file(source, "manifest.json")
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest.get("status") != "COMPLETE" or manifest.get("options", {}).get("format") != "jsonl":
        raise ValueError("Se requiere una exportación JSONL con estado COMPLETE")
    files = {entry["path"]: entry for entry in manifest["files"]}
    if len(files) != len(manifest["files"]):
        raise ValueError("Rutas duplicadas en el manifiesto")
    parts = sorted(name for name in files if name.endswith((".jsonl.gz", ".jsonl")) and name != "errors.jsonl")
    index_path = verify_file(source, files["records.sqlite"])
    options = manifest["options"]
    if "mapping.json" in files:
        mapping = json.loads(verify_file(source, files["mapping.json"]).read_text(encoding="utf-8"))
        for field in (args.title_field, args.abstract_field):
            if field not in mapping["fields"]:
                raise ValueError(f"El perfil de origen no declara data.{field}")

    output.parent.mkdir(parents=True, exist_ok=True)
    lock = output.with_name(f".{output.name}.lock")
    lock.mkdir()
    stage = None
    try:
        if output.exists():
            raise ValueError(f"Ya existe {output}")
        stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
        encoder = Encoder(args) if parts else None
        with closing(sqlite3.connect(index_path.as_uri() + "?mode=ro&immutable=1", uri=True)) as database:
            foreign = database.execute("SELECT 1 FROM records WHERE export_id != ? OR variant != ? LIMIT 1", (manifest["exportId"], options["variant"])).fetchone()
            if foreign is not None:
                raise ValueError("El SQLite no corresponde a la exportación o variante")
            rows = iter(database.execute(
                "SELECT file, position, network, catalog_id, identifier, metadata_hash "
                "FROM records WHERE status = 'EXPORTED' ORDER BY file COLLATE BINARY, position"
            ))
            inventory = []
            total = with_abstract = 0
            for part in parts:
                source_path = verify_file(source, files[part])
                relative = part.removesuffix(".gz").removesuffix(".jsonl") + ".embeddings.jsonl.gz"
                destination = stage / relative
                if destination.exists():
                    raise ValueError(f"Colisión de nombres de salida: {relative}")
                count, abstracts = write_part(source_path, part, destination, rows, encoder, args)
                total += count
                with_abstract += abstracts
                inventory.append({
                    "sourceFile": part, "sourceSha256": files[part]["sha256"],
                    "file": relative, "records": count, **file_entry(destination),
                })
                LOGGER.info("%s %d registros", relative, count)
            if next(rows, None) is not None or total != manifest["counts"]["exported"]:
                raise ValueError("La cantidad de filas no coincide entre el SQLite, los JSONL y el manifiesto")
        result = {
            "formatVersion": 1, "type": "metadata-embeddings", "status": "COMPLETE",
            "createdAt": datetime.now(timezone.utc).isoformat(), "format": "jsonl", "compression": "gzip",
            "source": {
                "exportId": manifest["exportId"], "manifestSha256": "sha256:" + hashlib.sha256(manifest_bytes).hexdigest(),
                "variant": options["variant"], "mappingHash": manifest.get("mappingHash"),
            },
            "embeddings": [{
                "id": "title-abstract", "model": MODEL_ID, "revision": args.revision,
                "dimensions": DIMENSIONS, "inferenceDtype": args.dtype, "outputDtype": "float32",
                "normalization": "l2", "maxTokens": args.max_tokens, "onOverflow": "error",
                "input": {
                    "titleField": args.title_field, "titleSelection": "first nonblank occurrence, trimmed",
                    "abstractField": args.abstract_field, "abstractQualifierRoot": args.abstract_qualifier,
                    "abstractSelection": "nonblank occurrences, trimmed, in source order; qualifier root matched case-insensitively",
                    "abstractSeparator": "\n\n", "fallback": "use title as text", "missingTitle": "none",
                    "documentTemplate": DOCUMENT_TEMPLATE, "queryTemplate": QUERY_TEMPLATE,
                },
            }],
            "runtime": {"device": args.device, "batchSizeLimit": args.batch_size, **(encoder.versions if encoder else {})},
            "counts": {"records": total, "withAbstract": with_abstract, "titleFallback": total - with_abstract},
            "parts": inventory,
        }
        (stage / "manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        stage.rename(output)
        LOGGER.info("Completo %s (%d registros)", output, total)
    finally:
        if stage is not None and stage.exists():
            shutil.rmtree(stage)
        lock.rmdir()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Carpeta de la exportación de metadata")
    parser.add_argument("output", type=Path, help="Carpeta nueva para el paquete de vectores")
    parser.add_argument("--revision", required=True, help="Commit de Hugging Face del modelo (40 caracteres hexadecimales)")
    parser.add_argument("--title-field", default="titles")
    parser.add_argument("--abstract-field", default="descriptions")
    parser.add_argument("--abstract-qualifier", default="abstract", metavar="ROOT",
                        help="Primer componente del qualifier. Usa una cadena vacía para un campo dedicado a abstracts")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--dtype", choices=("bfloat16", "float32"), default="bfloat16")
    parser.add_argument("--max-tokens", type=int, default=8192)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--chunk-size", type=int, default=512)
    parser.add_argument("--offline", action="store_true", help="Cargar la revisión solo desde la caché local")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("--revision debe ser un SHA exacto de 40 caracteres hexadecimales")
    if not (1 <= args.max_tokens <= 8192 and 1 <= args.batch_size <= 16 and args.chunk_size >= 1):
        parser.error("Se requiere 1–8192 tokens, 1–16 documentos por batch y un chunk positivo")
    args.abstract_qualifier = args.abstract_qualifier.strip().casefold() or None
    return args


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    try:
        build(args)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, ImportError, sqlite3.Error) as exc:
        LOGGER.error("%s", exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
