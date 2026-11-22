import argparse
import gzip
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compute_metadata_embeddings as compute


class PreparedInputTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name) / "prepared"
        self.output = self.root.with_name("vectors")
        self.root.mkdir()
        self.part = "part-000001.jsonl.gz"
        self.inputs = [
            {"network": "UY", "catalogId": "1", "text": "title: Un título | text: Resumen\n\ntema"},
            {"network": "AR", "catalogId": "1", "text": "title: Title | text: Mixed\r\nFunding note"},
            {"network": "UY", "catalogId": "2", "text": "title: Other | text: Other"},
        ]
        with gzip.open(self.root / self.part, "wt", encoding="utf-8") as out:
            for row in self.inputs:
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
        with gzip.open(self.root / "provenance.jsonl.gz", "wt", encoding="utf-8") as audit:
            audit.write('{"network":"UY","catalogId":"1","identifier":"oai:sample:1"}\n')
        self.manifest = {
            "packageType": "embedding-inputs", "contractVersion": 1, "status": "COMPLETE",
            "packageId": "test", "recipe": "combined-v1", "parts": [self.part],
            "counts": {"emitted": len(self.inputs)},
            "files": [{"path": name, **compute.file_entry(self.root / name)}
                      for name in (self.part, "provenance.jsonl.gz")],
        }
        self.args = argparse.Namespace(source=self.root, output=self.output, revision="f" * 40,
                                       on_overflow=None, recipe_id=None, chunk_size=2,
                                       max_tokens=32, batch_size=2, dtype="float32", device="cpu")
        self.manifest_file = self.root / "manifest.json"

    def test_sends_literal_text_and_keeps_vector_order(self):
        self.manifest_file.write_text(json.dumps(self.manifest))
        encoder = Mock(versions={})
        # Record the submitted strings before the writer clears its working batch.
        submitted = []

        def encode(texts):
            submitted.extend(texts)
            vectors = [Mock(tolist=lambda: [1.0, 0.0]) for _ in texts]
            return vectors, [(0, 40, 32)] if len(texts) == 2 else []

        encoder.encode.side_effect = encode
        with patch.object(compute, "Encoder", return_value=encoder):
            compute.build(self.args)
        self.assertEqual(submitted, [row["text"] for row in self.inputs])
        result = json.loads((self.output / "manifest.json").read_bytes())
        self.assertEqual(result["type"], "embedding-vectors")
        self.assertEqual(result["embeddings"][0]["onOverflow"], "truncate")
        self.assertEqual(result["truncatedInputs"], [
            {"sourceFile": self.part, "sourceLine": 1, "sourceTokens": 40, "encodedTokens": 32},
        ])
        with gzip.open(self.output / result["parts"][0]["file"], "rt", encoding="utf-8") as source:
            vectors = [json.loads(line) for line in source]
        self.assertEqual(vectors, [{"network": row["network"], "catalogId": row["catalogId"], "vector": [1.0, 0.0]}
                                   for row in self.inputs])

    def test_invalid_data_checksum_prevents_publication(self):
        self.manifest_file.write_text(json.dumps(self.manifest))
        (self.root / self.part).write_bytes(b"invalid")
        with patch.object(compute, "Encoder"):
            with self.assertRaisesRegex(ValueError, self.part):
                compute.build(self.args)
        self.assertFalse(self.output.exists())

    def test_partial_package_is_rejected_before_loading_model(self):
        self.manifest["status"] = "PARTIAL"
        self.manifest_file.write_text(json.dumps(self.manifest))
        with patch.object(compute, "Encoder") as encoder:
            with self.assertRaisesRegex(ValueError, "COMPLETE"):
                compute.build(self.args)
        encoder.assert_not_called()

    def test_raw_export_keeps_legacy_text_and_vector_shape(self):
        row = {"network": "UY", "catalogId": "1", "identifier": "oai:1", "metadataHash": "abc",
               "data": {"titles": [{"value": "First"}, {"value": "Second"}],
                        "descriptions": [{"value": "Description"}, {"value": "Abstract", "qualifier": "abstract"}]}}
        part = self.root / "legacy.jsonl.gz"
        with gzip.open(part, "wt", encoding="utf-8") as stream:
            stream.write(json.dumps(row) + "\n")
        index = iter([(part.name, 1, "UY", "1", "oai:1", "abc")])
        destination = self.root / "legacy.embeddings.jsonl.gz"
        self.args.title_field = "titles"
        self.args.abstract_field = "descriptions"
        self.args.abstract_qualifier = "abstract"
        encoder = Mock()
        encoder.encode.return_value = ([Mock(tolist=lambda: [1.0])], [])
        self.assertEqual(compute.write_part(part, part.name, destination, index, encoder, self.args, []), (1, 1))
        encoder.encode.assert_called_once_with(["title: First | text: Abstract"])
        with gzip.open(destination, "rt", encoding="utf-8") as stream:
            self.assertEqual(json.load(stream), [[1.0]])
