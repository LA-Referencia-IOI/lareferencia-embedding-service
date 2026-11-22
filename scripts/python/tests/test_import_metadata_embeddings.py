"""Integration tests use a newly created database on an explicitly disposable server."""
import os
import gzip
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_metadata_embeddings as importer
from test_sqlite_vector_package import create_fixture, write_manifest


@unittest.skipUnless(os.getenv("DVP_PYTHON_TEST_DSN"), "Set DVP_PYTHON_TEST_DSN to a disposable PostgreSQL server")
class SharedIndexImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg
        from psycopg import sql
        from psycopg.conninfo import make_conninfo

        cls.psycopg = psycopg
        root = os.environ["DVP_PYTHON_TEST_DSN"]
        name = "dvp_python_test_" + uuid.uuid4().hex
        with psycopg.connect(root, autocommit=True) as conn:
            conn.execute(sql.SQL("CREATE DATABASE {} ").format(sql.Identifier(name)))
        cls.dsn = make_conninfo(root, dbname=name)
        with psycopg.connect(cls.dsn) as conn:
            conn.execute("CREATE EXTENSION vector")
            script = Path(__file__).resolve().parents[3] / "src/main/resources/db/migration/V2__initialize_v2.sql"
            conn.execute(script.read_text())

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.previous = os.environ.get("DVP_PYTHON_CASE_DSN")
        os.environ["DVP_PYTHON_CASE_DSN"] = self.dsn
        self.addCleanup(self.restore_env)

    def restore_env(self):
        if self.previous is None:
            os.environ.pop("DVP_PYTHON_CASE_DSN", None)
        else:
            os.environ["DVP_PYTHON_CASE_DSN"] = self.previous

    def load(self, recipe, collection, ann="none"):
        package = SimpleNamespace(recipes=[recipe], count=1)
        package.records = lambda: (item for item in [("external-id", {"title": "Example"}, [], 0, ["[1.0,2.0]"])])
        args = SimpleNamespace(dsn_env="DVP_PYTHON_CASE_DSN", report=Path(self.tmp.name) / (collection + ".json"),
                               collection_slug=collection, collection_name=collection, ann=ann, batch_size=1)
        report = {"collection": {"slug": collection}, "indexations": [{"slug": recipe["id"]}]}
        return importer.apply(args, package, report)

    def recipe(self, name, model="example-model"):
        return {"id": name, "model": model, "revision": "revision-1", "dimensions": 2,
                "input": {"queryTemplate": "query: {query}"}}

    def test_reuses_shared_index_and_rejects_profile_conflict_atomically(self):
        suffix = uuid.uuid4().hex
        recipe = self.recipe("idx-" + suffix)
        first = self.load(recipe, "a-" + suffix)
        second = self.load(recipe, "b-" + suffix)
        a, b = first["indexations"][0], second["indexations"][0]
        self.assertFalse(a["reused"])
        self.assertTrue(b["reused"])
        self.assertEqual(a["id"], b["id"])
        self.assertEqual(a["profileId"], b["profileId"])
        with self.psycopg.connect(self.dsn) as conn:
            self.assertEqual(2, conn.execute("SELECT count(*) FROM dvp.collection_indexations WHERE indexation_id=%s", (a["id"],)).fetchone()[0])
            self.assertEqual(2, conn.execute("SELECT count(*) FROM dvp.vector_units WHERE indexation_id=%s", (a["id"],)).fetchone()[0])
            self.assertEqual(2, conn.execute("SELECT count(*) FROM dvp.documents WHERE collection_id IN (%s,%s)", (first["collection"]["id"], second["collection"]["id"])).fetchone()[0])
        with self.assertRaisesRegex(ValueError, "incompatible"):
            self.load(recipe, "strategy-" + suffix, "hnsw_vector")
        with self.assertRaisesRegex(ValueError, "incompatible"):
            self.load(self.recipe(recipe["id"], "different-model"), "conflict-" + suffix)
        with self.psycopg.connect(self.dsn) as conn:
            self.assertEqual(0, conn.execute("SELECT count(*) FROM dvp.collections WHERE slug=%s", ("conflict-" + suffix,)).fetchone()[0])
            self.assertEqual(0, conn.execute("SELECT count(*) FROM dvp.embedding_profiles WHERE model='different-model'").fetchone()[0])

    def test_reuses_ready_hnsw_without_rebuilding(self):
        suffix = uuid.uuid4().hex
        recipe = self.recipe("ann-" + suffix)
        first = self.load(recipe, "ann-a-" + suffix, "hnsw_vector")
        second = self.load(recipe, "ann-b-" + suffix, "hnsw_vector")
        self.assertEqual(first["indexations"][0]["id"], second["indexations"][0]["id"])
        self.assertTrue(second["indexations"][0]["reused"])
        with self.psycopg.connect(self.dsn) as conn:
            self.assertEqual("ready", conn.execute("SELECT ann_state FROM dvp.indexations WHERE id=%s", (first["indexations"][0]["id"],)).fetchone()[0])

    def test_cli_imports_complete_paired_package(self):
        root = Path(self.tmp.name)
        source, vectors = root / "source", root / "vectors"
        source.mkdir();vectors.mkdir()
        document = {"network": "example", "catalogId": "catalog", "identifier": "document", "metadataHash": "hash",
                    "data": {"titles": [{"value": "Open science", "language": "en"}]}}
        part = source / "part-000.jsonl"
        part.write_text(json.dumps(document) + "\n", encoding="utf-8")
        with sqlite3.connect(source / "records.sqlite") as db:
            db.execute("CREATE TABLE records (file,position,network,catalog_id,identifier,metadata_hash,record_key,export_id,variant,status)")
            db.execute("INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?)", (part.name, 1, "example", "catalog", "document", "hash", "example:document", "export-1", "v1", "EXPORTED"))
        source_manifest = {"contractVersion": 2, "status": "COMPLETE", "exportId": "export-1", "options": {"format": "jsonl", "variant": "v1"},
                           "mappingHash": "mapping", "counts": {"exported": 1}, "files": [
                               {"path": part.name, **importer.file_entry(part)},
                               {"path": "records.sqlite", **importer.file_entry(source / "records.sqlite")}]}
        (source / "manifest.json").write_text(json.dumps(source_manifest), encoding="utf-8")
        vector_part = vectors / "part-000.jsonl.gz"
        with gzip.open(vector_part, "wt", encoding="utf-8") as stream:
            stream.write("[[1.0,2.0]]\n")
        recipe = {**self.recipe("cli-" + uuid.uuid4().hex), "outputDtype": "float32"}
        vector_manifest = {"formatVersion": 1, "type": "metadata-embeddings", "status": "COMPLETE", "format": "jsonl", "compression": "gzip",
                           "source": {"exportId": "export-1", "variant": "v1", "mappingHash": "mapping", "manifestSha256": importer.file_entry(source / "manifest.json")["sha256"]},
                           "counts": {"records": 1}, "embeddings": [recipe], "parts": [{"sourceFile": part.name,
                               "sourceSha256": importer.file_entry(part)["sha256"], "file": vector_part.name,
                               "records": 1, **importer.file_entry(vector_part)}]}
        (vectors / "manifest.json").write_text(json.dumps(vector_manifest), encoding="utf-8")
        report = root / "cli-report.json"
        result = subprocess.run([sys.executable, str(Path(importer.__file__)), str(source), str(vectors), "--collection-slug", "cli-" + uuid.uuid4().hex,
                                 "--collection-name", "CLI test", "--dsn-env", "DVP_PYTHON_CASE_DSN", "--report", str(report)], capture_output=True, text=True, env=os.environ.copy())
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(report.read_text())
        self.assertEqual("COMPLETE", output["status"])
        self.assertEqual(1, output["counts"]["documents"])
        self.assertEqual(1, output["counts"]["texts"])
        with self.psycopg.connect(self.dsn) as conn:
            self.assertEqual(1, conn.execute("SELECT count(*) FROM dvp.collection_indexations WHERE indexation_id=%s AND collection_id=%s", (output["indexations"][0]["id"], output["collection"]["id"])).fetchone()[0])


    def test_imports_sqlite_and_rolls_back_invalid_vector(self):
        root = Path(self.tmp.name)
        source, edition = create_fixture(root, converted=True)
        report = root / "sqlite-report.json"
        collection = "sqlite-" + uuid.uuid4().hex
        args = [sys.executable, "-B", importer.__file__, str(source), str(edition),
                "--collection-slug", collection, "--collection-name", "SQLite CLI test",
                "--dsn-env", "DVP_PYTHON_CASE_DSN", "--report", str(report), "--batch-size", "1"]
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(report.read_text())
        self.assertEqual(output["counts"], {"documents": 2, "texts": 2, "blankTextsSkipped": 0, "vectorsPerRecipe": 2})
        with self.psycopg.connect(self.dsn) as conn:
            actual = conn.execute("""
                SELECT d.external_id, d.metadata, s.content
                FROM dvp.documents d JOIN dvp.source_texts s ON s.document_id=d.id
                WHERE d.collection_id=%s ORDER BY d.external_id
            """, (output["collection"]["id"],)).fetchall()
            self.assertEqual(actual[0], ("AR_1", {"network": "AR", "catalogId": "1"},
                                         "title: Título | text: Resumen\n\nclave"))
            table = self.psycopg.sql.Identifier(*output["indexations"][0]["vectorTable"].split("."))
            actual = conn.execute(self.psycopg.sql.SQL("""
                SELECT d.external_id, v.embedding::text, v.origin
                FROM {} v JOIN dvp.documents d ON d.id=v.document_id
                WHERE d.collection_id=%s ORDER BY d.external_id
            """).format(table), (output["collection"]["id"],)).fetchall()
            self.assertEqual(actual, [("AR_1", "[1.25,-0.5]", "imported"), ("BO_1", "[2.25,-0.5]", "imported")])

        db = edition / "embeddings.sqlite"
        with sqlite3.connect(db) as conn:
            conn.execute("UPDATE vectors SET vector=? WHERE item=2", (bytes(8),))
        manifest = edition / "manifest.json"
        data = json.loads(manifest.read_text())
        data["files"][0].update(importer.file_entry(db))
        write_manifest(manifest, data)
        cancelled = collection + "-invalid"
        args[args.index(collection)] = cancelled
        invalid_report = root / "invalid-report.json"
        args[args.index(str(report))] = str(invalid_report)
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("zero vector", result.stderr)
        self.assertFalse(invalid_report.exists())
        with self.psycopg.connect(self.dsn) as conn:
            self.assertIsNone(conn.execute("SELECT id FROM dvp.collections WHERE slug=%s", (cancelled,)).fetchone())


if __name__ == "__main__":
    unittest.main()
