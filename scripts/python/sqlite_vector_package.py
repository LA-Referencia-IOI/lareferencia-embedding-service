"""Read and validate the metadata-tools SQLite vector interchange format."""
import hashlib
import json
import re
import sqlite3
from contextlib import closing
from pathlib import PurePosixPath

from compute_metadata_embeddings import package_file, verify_file

APPLICATION_ID = 0x4C524556  # LREV
SHA256 = re.compile(r"sha256:[0-9a-f]{64}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def valid_checksum(value):
    return isinstance(value, str) and SHA256.fullmatch(value)


def nonnegative(value):
    return type(value) is int and value >= 0


def part_name(value):
    if not isinstance(value, str):
        return False
    name = PurePosixPath(value)
    return bool(name.parts) and not name.is_absolute() and ".." not in name.parts and str(name) == value


class SQLiteVectorEdition:
    def __init__(self, root):
        root = root.resolve(strict=True)
        raw = package_file(root, "manifest.json").read_bytes()
        manifest = self.manifest = json.loads(raw)
        self.manifest_hash = "sha256:" + hashlib.sha256(raw).hexdigest()
        require(manifest.get("type") == "embedding-vectors" and type(manifest.get("formatVersion")) is int
                and manifest["formatVersion"] == 1 and manifest.get("format") == "sqlite"
                and manifest.get("status") == "COMPLETE" and manifest.get("encoding") == "float32le",
                "Expected a COMPLETE embedding-vectors v1 SQLite edition with float32le encoding")
        files = manifest["files"]
        require(isinstance(files, list) and len(files) == 1 and files[0]["path"] == "embeddings.sqlite",
                "SQLite edition must list exactly one embeddings.sqlite")
        self.database = verify_file(root, files[0])
        self.count = manifest["counts"]["records"]
        require(nonnegative(self.count), "Invalid SQLite edition record count")
        self.recipes = manifest["embeddings"]
        require(isinstance(self.recipes, list) and len(self.recipes) == 1, "Expected exactly one embedding recipe")

        source = manifest["source"]
        self.input = source["input"]
        require(valid_checksum(self.input["manifestSha256"]), "Invalid input manifest checksum")
        identifiers = [self.input.get(key) for key in ("packageId", "exportId") if key in self.input]
        require(len(identifiers) == 1 and isinstance(identifiers[0], str) and identifiers[0].strip(),
                "Expected an input package ID or export ID")
        converted = "vectorManifestSha256" in source
        if converted:
            require(valid_checksum(source["vectorManifestSha256"]), "Invalid JSONL vector manifest checksum")

        self.parts = manifest["parts"]
        require(isinstance(self.parts, list), "Expected a SQLite part inventory")
        seen, vector_files = set(), set()
        for part in self.parts:
            name = part["sourceFile"]
            require(part_name(name) and name not in seen and valid_checksum(part["sourceSha256"])
                    and nonnegative(part["records"]), f"Invalid or duplicate SQLite input part: {name}")
            seen.add(name)
            if converted:
                name = part["file"]
                require(part_name(name) and name not in vector_files and name.endswith(".jsonl.gz")
                        and valid_checksum(part["sha256"]) and nonnegative(part["bytes"]),
                        f"Invalid or duplicate JSONL vector part: {name}")
                vector_files.add(name)
            else:
                require(nonnegative(part["sourceBytes"]), f"Invalid input size: {name}")
        require(sum(part["records"] for part in self.parts) == self.count, "SQLite part counts do not reconcile")

    def records(self):
        with closing(sqlite3.connect(self.database.as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
            require(db.execute("PRAGMA application_id").fetchone() == (APPLICATION_ID,)
                    and db.execute("PRAGMA user_version").fetchone() == (1,),
                    "Unexpected SQLite vector application ID or schema version")
            require(db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
                    and db.execute("PRAGMA foreign_key_check").fetchone() is None, "Invalid SQLite vector database")
            metadata = db.execute("SELECT singleton, metadata FROM package").fetchmany(2)
            expected = {k: v for k, v in self.manifest.items() if k != "files"}
            require(len(metadata) == 1 and metadata[0][0] == 1 and json.loads(metadata[0][1]) == expected,
                    "SQLite package metadata does not match manifest.json")
            require(db.execute("SELECT count(*) FROM vectors").fetchone()[0] == self.count,
                    "SQLite vector count does not match manifest.json")
            # Document IDs use the same network_catalogId convention as the Java export.
            require(db.execute("SELECT 1 FROM items GROUP BY network || '_' || catalog_id HAVING count(*) > 1 LIMIT 1").fetchone()
                    is None, "Duplicate document identity in SQLite edition")
            items = iter(db.execute("SELECT id, part, line, network, catalog_id FROM items ORDER BY part, line"))
            parts = iter(db.execute("SELECT id, metadata FROM parts ORDER BY id"))
            for part in self.parts:
                indexed = next(parts, None)
                name = part["sourceFile"]
                require(indexed is not None and json.loads(indexed[1]) == part, f"SQLite part differs from manifest: {name}")
                for line in range(1, part["records"] + 1):
                    item = next(items, None)
                    require(item is not None and item[1:3] == (indexed[0], line), f"Invalid SQLite locator: {name}:{line}")
                    # Sort only identities above and read each BLOB by primary key.
                    rows = db.execute("SELECT vector FROM vectors WHERE item=?", (item[0],)).fetchmany(2)
                    require(len(rows) == 1 and isinstance(rows[0][0], bytes), f"Invalid vector BLOB: {name}:{line}")
                    yield (name, line, item[3], item[4]), rows[0][0]
            require(next(parts, None) is None and next(items, None) is None, "SQLite contains undeclared parts or items")
