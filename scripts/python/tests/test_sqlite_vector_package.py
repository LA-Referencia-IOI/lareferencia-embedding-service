import gzip
import json
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_metadata_embeddings as importer


def write_manifest(path, manifest):
    path.write_text(json.dumps(manifest) + "\n", encoding="utf-8")


def create_fixture(root, *, converted=False):
    source, edition = root / "prepared", root / "edition"
    source.mkdir()
    edition.mkdir()
    name = "part-000001.jsonl.gz"
    rows = [
        {"network": "AR", "catalogId": "1", "text": "title: Título | text: Resumen\n\nclave"},
        {"network": "BO", "catalogId": "1", "text": "title: Título | text: Otro resumen"},
    ]
    with gzip.open(source / name, "wt", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
    src_file = {"path": name, **importer.file_entry(source / name)}
    write_manifest(source / "manifest.json", {
        "packageType": "embedding-inputs", "contractVersion": 1, "status": "COMPLETE",
        "packageId": "prepared", "parts": [name], "counts": {"emitted": len(rows)}, "files": [src_file],
    })
    part = {"sourceFile": name, "sourceSha256": src_file["sha256"], "records": len(rows)}
    provenance = {"input": {"packageId": "prepared", "manifestSha256": importer.file_entry(source / "manifest.json")["sha256"]}}
    if converted:
        provenance["vectorManifestSha256"] = "sha256:" + "a" * 64
        part.update(file="part-000001.embeddings.jsonl.gz", sha256="sha256:" + "b" * 64, bytes=42)
    else:
        part["sourceBytes"] = src_file["bytes"]
    manifest = {
        "type": "embedding-vectors", "formatVersion": 1, "format": "sqlite", "status": "COMPLETE", "encoding": "float32le",
        "source": provenance, "counts": {"records": len(rows)}, "parts": [part],
        "embeddings": [{"id": "combined-test", "dimensions": 2, "model": "model", "revision": "rev", "outputDtype": "float32",
                        "input": {"queryTemplate": "query: {query}", "textField": "text"}}],
    }
    db_path = edition / "embeddings.sqlite"
    with sqlite3.connect(db_path) as db:
        db.executescript("""
            PRAGMA application_id=1280460118;
            PRAGMA user_version=1;
            CREATE TABLE package(singleton INTEGER PRIMARY KEY, metadata TEXT);
            CREATE TABLE parts(id INTEGER PRIMARY KEY, metadata TEXT);
            CREATE TABLE items(id INTEGER PRIMARY KEY, network TEXT, catalog_id TEXT, part INTEGER REFERENCES parts(id), line INTEGER);
            CREATE TABLE vectors(item INTEGER PRIMARY KEY REFERENCES items(id), vector BLOB);
        """)
        db.execute("INSERT INTO package VALUES (1,?)", (json.dumps(manifest),))
        db.execute("INSERT INTO parts VALUES (1,?)", (json.dumps(part),))
        for i, row in enumerate(rows, 1):
            db.execute("INSERT INTO items VALUES (?,?,?,1,?)", (i, row["network"], row["catalogId"], i))
            db.execute("INSERT INTO vectors VALUES (?,?)", (i, struct.pack("<2f", i + 0.25, -0.5)))
    write_manifest(edition / "manifest.json", {**manifest, "files": [{"path": db_path.name, **importer.file_entry(db_path)}]})
    return source, edition


class SQLiteVectorPackageTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.source, self.edition = create_fixture(self.root)

    def mutate(self, statement, parameters=()):
        path = self.edition / "embeddings.sqlite"
        with sqlite3.connect(path) as db:
            db.execute(statement, parameters)
        manifest_path = self.edition / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][0].update(importer.file_entry(path))
        write_manifest(manifest_path, manifest)

    def test_edition_only_dry_run(self):
        completed = subprocess.run([
            sys.executable, "-B", importer.__file__, str(self.edition), "--collection-slug", "example",
            "--collection-name", "Example", "--dry-run",
        ], text=True, capture_output=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout)["counts"], {
            "documents": 2, "texts": 0, "blankTextsSkipped": 0, "vectorsPerRecipe": 2,
        })

    def test_paired_edition_retains_exact_text_and_distinct_networks(self):
        for converted in (False, True):
            with self.subTest(converted=converted):
                if converted:
                    root = self.root / "converted"
                    root.mkdir()
                    source, edition = create_fixture(root, converted=True)
                else:
                    source, edition = self.source, self.edition
                package = importer.open_package(source, edition, "none")
                rows = list(package.records())
                self.assertEqual([row[0] for row in rows], ["AR_1", "BO_1"])
                self.assertEqual(rows[0][1], {"network": "AR", "catalogId": "1"})
                self.assertEqual(rows[0][2][0][1:4], ("body", None, "title: Título | text: Resumen\n\nclave"))
                self.assertEqual(json.loads(rows[0][-1][0]), [1.25, -0.5])
                self.assertEqual(json.loads(rows[1][-1][0]), [2.25, -0.5])

    def test_wrong_source_manifest(self):
        with (self.source / "manifest.json").open("a") as stream:
            stream.write("\n")
        with self.assertRaisesRegex(ValueError, "Source manifest"):
            importer.open_package(self.source, self.edition, "none")

    def test_wrong_database_checksum(self):
        with (self.edition / "embeddings.sqlite").open("ab") as stream:
            stream.write(b"broken")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            importer.open_package(self.edition, None, "none")

    def test_source_identity_mismatch(self):
        self.mutate("UPDATE items SET catalog_id='2' WHERE id=1")
        with self.assertRaisesRegex(ValueError, "identity or position"):
            list(importer.open_package(self.source, self.edition, "none").records())

    def test_invalid_vector_payloads(self):
        for payload, message in ((b"short", "float32 vector size"), (struct.pack("<2f", 0, 0), "zero"),
                                 (struct.pack("<2f", float("inf"), 1), "Non-finite")):
            with self.subTest(payload=payload):
                self.mutate("UPDATE vectors SET vector=? WHERE item=1", (payload,))
                with self.assertRaisesRegex(ValueError, message):
                    list(importer.open_package(self.edition, None, "none").records())

    def test_wrong_locator(self):
        self.mutate("UPDATE items SET line=3 WHERE id=2")
        with self.assertRaisesRegex(ValueError, "locator"):
            list(importer.open_package(self.edition, None, "none").records())

    def test_database_embedded_manifest_mismatch(self):
        self.mutate("UPDATE package SET metadata='{}'")
        with self.assertRaisesRegex(ValueError, "metadata does not match"):
            list(importer.open_package(self.edition, None, "none").records())
