#!/usr/bin/env python3
"""Compute embeddings from a metadata export or a package of prepared texts."""
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
from contextlib import closing, nullcontext
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

    def truncate(self, text):
        # Offsets keep the original prefix and spacing. Counting again accounts
        # for token boundaries that can change when the suffix is removed.
        offsets = self.tokenizer(
            text, truncation=True, max_length=self.args.max_tokens,
            add_special_tokens=True, return_offsets_mapping=True,
        )["offset_mapping"]
        for end in sorted({end for start, end in offsets if end > start}, reverse=True):
            prefix = text[:end]
            count = len(self.tokenizer(prefix, truncation=False, add_special_tokens=True, verbose=False)["input_ids"])
            if count <= self.args.max_tokens:
                return prefix, count
        raise ValueError("El límite no deja espacio para el contenido de la entrada")

    def encode(self, texts):
        np = self.np
        texts = list(texts)
        truncated = []
        lengths = self.tokenizer(texts, truncation=False, padding=False, add_special_tokens=True, verbose=False)["input_ids"]
        counts = [len(ids) for ids in lengths]
        for index, count in enumerate(counts):
            if count > self.args.max_tokens:
                if self.args.on_overflow == "error":
                    raise ValueError(f"Entrada {index + 1} del lote supera {self.args.max_tokens} tokens ({count})")
                texts[index], counts[index] = self.truncate(texts[index])
                truncated.append((index, count, counts[index]))
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
        return vectors, truncated


def write_batch(stream, encoder, texts, source_file, first_line, truncations, identities):
    try:
        vectors, truncated = encoder.encode(texts)
    except (RuntimeError, ValueError) as exc:
        raise ValueError(f"{source_file}, líneas {first_line}–{first_line + len(texts) - 1}: {exc}") from exc
    for offset, source_tokens, encoded_tokens in truncated:
        line = first_line + offset
        truncations.append({"sourceFile": source_file, "sourceLine": line,
                            "sourceTokens": source_tokens, "encodedTokens": encoded_tokens})
        LOGGER.info("Recorte de entrada %s:%d (%d → %d tokens)", source_file, line, source_tokens, encoded_tokens)
    for index, vector in enumerate(vectors):
        if identities is None:
            # Nine significant digits round-trip float32. Preserve signed zero too.
            values = (format(value, ".9g") for value in vector.tolist())
            stream.write("[[" + ",".join("-0.0" if value == "-0" else value for value in values) + "]]\n")
        else:
            network, catalog_id = identities[index]
            record = {"network": network, "catalogId": catalog_id, "vector": vector.tolist()}
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")


def write_part(source_path, source_file, destination, index_rows, encoder, args, truncations):
    count = with_abstract = 0
    texts = []
    identities = [] if index_rows is None else None
    read = gzip.open if source_file.endswith(".gz") else open
    destination.parent.mkdir(parents=True, exist_ok=True)
    with read(source_path, "rt", encoding="utf-8") as source, gzip.open(destination, "wt", encoding="utf-8", compresslevel=3) as output:
        for count, line in enumerate(source, 1):
            try:
                record = json.loads(line)
                if identities is None:
                    identity = (source_file, count, record["network"], record["catalogId"], record["identifier"], record["metadataHash"])
                    if next(index_rows, None) != identity:
                        raise ValueError("La posición, identidad o hash no coincide con records.sqlite")
                    text, has_abstract = input_text(record, args)
                    with_abstract += has_abstract
                else:
                    identity = (record["network"], record["catalogId"])
                    text = record["text"]
                    if not all(isinstance(value, str) and value.strip() for value in (*identity, text)):
                        raise ValueError("Expected nonblank network, catalogId and text")
                    identities.append(identity)
            except (ValueError, KeyError, TypeError) as exc:
                raise ValueError(f"{source_file}:{count}: {exc}") from exc
            texts.append(text)
            if len(texts) == args.chunk_size:
                write_batch(output, encoder, texts, source_file, count - len(texts) + 1, truncations, identities)
                texts.clear()
                if identities is not None:
                    identities.clear()
        if texts:
            write_batch(output, encoder, texts, source_file, count - len(texts) + 1, truncations, identities)
    return count, with_abstract


def indexed_rows(database, manifest):
    if database is None:
        return None
    foreign = database.execute("SELECT 1 FROM records WHERE export_id != ? OR variant != ? LIMIT 1",
                               (manifest["exportId"], manifest["options"]["variant"])).fetchone()
    if foreign is not None:
        raise ValueError("El SQLite no corresponde a la exportación o variante")
    return iter(database.execute(
        "SELECT file, position, network, catalog_id, identifier, metadata_hash "
        "FROM records WHERE status = 'EXPORTED' ORDER BY file COLLATE BINARY, position"
    ))


def build(args):
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    if output.exists() or output.is_relative_to(source):
        raise ValueError("La salida debe ser una carpeta nueva fuera del paquete de origen")
    manifest_path = package_file(source, "manifest.json")
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    prepared = manifest.get("packageType") == "embedding-inputs"
    if manifest.get("status") != "COMPLETE":
        raise ValueError("Expected a COMPLETE input package")
    files = {entry["path"]: entry for entry in manifest["files"]}
    if len(files) != len(manifest["files"]):
        raise ValueError("Duplicate paths in input manifest")
    if prepared:
        if manifest["contractVersion"] != 1:
            raise ValueError("Unsupported embedding-inputs contract version")
        parts = manifest["parts"]
        if (not isinstance(parts, list) or len(set(parts)) != len(parts)
                or any(not part.endswith(".jsonl.gz") or part == "provenance.jsonl.gz" or part not in files for part in parts)):
            raise ValueError("Invalid embedding-inputs parts")
        expected = manifest["counts"]["emitted"]
        if type(expected) is not int or expected < 0:
            raise ValueError("Invalid embedding-inputs count")
        index_path = None
    else:
        if manifest.get("options", {}).get("format") != "jsonl":
            raise ValueError("Expected a JSONL metadata export")
        parts = sorted(name for name in files if name.endswith((".jsonl.gz", ".jsonl")) and name != "errors.jsonl")
        expected = manifest["counts"]["exported"]
        index_path = verify_file(source, files["records.sqlite"])
        if "mapping.json" in files:
            mapping = json.loads(verify_file(source, files["mapping.json"]).read_text(encoding="utf-8"))
            for field in (args.title_field, args.abstract_field):
                if field not in mapping["fields"]:
                    raise ValueError(f"El perfil de origen no declara data.{field}")
    if args.on_overflow is None:
        args.on_overflow = "truncate" if prepared else "error"
    if args.recipe_id is None:
        args.recipe_id = manifest["recipe"] if prepared else "title-abstract"
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,99}", args.recipe_id):
        raise ValueError("Invalid embedding recipe ID")

    output.parent.mkdir(parents=True, exist_ok=True)
    lock = output.with_name(f".{output.name}.lock")
    lock.mkdir()
    stage = None
    try:
        if output.exists():
            raise ValueError(f"Ya existe {output}")
        stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
        truncations = []
        encoder = Encoder(args) if parts else None
        index = closing(sqlite3.connect(index_path.as_uri() + "?mode=ro&immutable=1", uri=True)) if index_path else nullcontext()
        with index as database:
            rows = indexed_rows(database, manifest)
            inventory = []
            total = with_abstract = 0
            for part in parts:
                source_path = verify_file(source, files[part])
                relative = part.removesuffix(".gz").removesuffix(".jsonl") + ".embeddings.jsonl.gz"
                destination = stage / relative
                if destination.exists():
                    raise ValueError(f"Colisión de nombres de salida: {relative}")
                count, abstracts = write_part(source_path, part, destination, rows, encoder, args, truncations)
                total += count
                with_abstract += abstracts
                inventory.append({
                    "sourceFile": part, "sourceSha256": files[part]["sha256"],
                    "file": relative, "records": count, **file_entry(destination),
                })
                LOGGER.info("%s %d registros", relative, count)
            if total != expected or (rows is not None and next(rows, None) is not None):
                raise ValueError("Input record counts do not reconcile")
        result = {
            "formatVersion": 1, "type": "embedding-vectors" if prepared else "metadata-embeddings", "status": "COMPLETE",
            "createdAt": datetime.now(timezone.utc).isoformat(), "format": "jsonl", "compression": "gzip",
            "source": {"packageId": manifest["packageId"], "manifestSha256": "sha256:" + hashlib.sha256(manifest_bytes).hexdigest()}
            if prepared else {
                "exportId": manifest["exportId"], "manifestSha256": "sha256:" + hashlib.sha256(manifest_bytes).hexdigest(),
                "variant": manifest["options"]["variant"], "mappingHash": manifest.get("mappingHash"),
            },
            "embeddings": [{
                "id": args.recipe_id, "model": MODEL_ID, "revision": args.revision,
                "dimensions": DIMENSIONS, "inferenceDtype": args.dtype, "outputDtype": "float32",
                "normalization": "l2", "maxTokens": args.max_tokens, "onOverflow": args.on_overflow,
                "input": {"textField": "text", "preparationRecipe": manifest["recipe"], "queryTemplate": QUERY_TEMPLATE}
                if prepared else {
                    "titleField": args.title_field, "titleSelection": "first nonblank occurrence, trimmed",
                    "abstractField": args.abstract_field, "abstractQualifierRoot": args.abstract_qualifier,
                    "abstractSelection": (
                        "all nonblank occurrences, trimmed, in source order" if args.abstract_qualifier is None else
                        "nonblank occurrences, trimmed, in source order; qualifier root matched case-insensitively"
                    ),
                    "abstractSeparator": "\n\n", "fallback": "use title as text", "missingTitle": "none",
                    "documentTemplate": DOCUMENT_TEMPLATE, "queryTemplate": QUERY_TEMPLATE,
                },
            }],
            "runtime": {"device": args.device, "batchSizeLimit": args.batch_size, **(encoder.versions if encoder else {})},
            "counts": {"records": total} if prepared else {"records": total, "withAbstract": with_abstract, "titleFallback": total - with_abstract},
            "parts": inventory,
        }
        if args.on_overflow == "truncate":
            result["counts"]["truncated"] = len(truncations)
            result["truncatedInputs"] = truncations
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
    parser.add_argument("--on-overflow", choices=("error", "truncate"),
                        help="Token overflow policy (default: truncate for prepared texts, error for metadata exports)")
    parser.add_argument("--recipe-id", help="Recipe ID for the output manifest (default: input recipe or title-abstract)")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--chunk-size", type=int, default=512)
    parser.add_argument("--offline", action="store_true", help="Cargar la revisión solo desde la caché local")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("--revision debe ser un SHA exacto de 40 caracteres hexadecimales")
    if not (1 <= args.max_tokens <= 8192 and 1 <= args.batch_size <= 16 and args.chunk_size >= 1):
        parser.error("Se requiere 1–8192 tokens, 1–16 documentos por batch y un chunk positivo")
    if args.recipe_id is not None and not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,99}", args.recipe_id):
        parser.error("--recipe-id debe ser un slug de receta válido")
    args.abstract_qualifier = args.abstract_qualifier.strip().casefold() or None
    return args


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    try:
        build(args)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, ImportError, sqlite3.Error, EOFError) as exc:
        LOGGER.error("%s", exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
