#!/usr/bin/env python3
"""Fast stdlib tests for input validation, ZIP safety and deterministic generation."""
import contextlib
import importlib.util
import io
import hashlib
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from pathlib import Path


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


inventory = load("inventory", "inventory-promotion.py")
generator = load("generator", "generate-experiments.py")
benchmark = load("benchmark", "promo-benchmark.py")


class PromotionToolsTest(unittest.TestCase):
    def test_inferior_retry_cannot_overwrite_better_equal_rounded_score(self):
        with tempfile.TemporaryDirectory() as folder:
            previous_cwd = Path.cwd()
            os.chdir(folder)
            try:
                Path("input.in").write_text("input")
                Path("judge.exe").write_text("judge")
                config = benchmark.CANDIDATES["dynamic-repack"]
                signature = hashlib.sha256(json.dumps(config,sort_keys=True).encode()).hexdigest()[:12]
                key = f"{benchmark.digest('input.in')[:12]}-retry-{signature}"
                output = Path("area/solutions")/f"{key}-1.txt"
                output.parent.mkdir(parents=True)
                output.write_text("better archived placement")
                saved = Path("area/runs")/f"{key}.json"
                saved.parent.mkdir(parents=True)
                original = dict(status="failed",solution=output.as_posix(),solution_sha256=benchmark.digest(output),
                                official_score=1,raw_objective=100)
                saved.write_text(json.dumps(original))

                def worker(command, **kwargs):
                    work = Path(command[6])
                    (work/"00-construction.txt").write_text("inferior placement")
                    (work/"stages.csv").write_text("stage,operation,internal_score,raw_objective,algorithm_ms,passes,accepted,termination,solution\n"
                            "0,construction,1,50,1.0,0,0,done,00-construction.txt\n")
                    return type("Process",(),{"returncode":0})()

                with patch.object(benchmark.subprocess,"run",side_effect=worker), patch.object(benchmark,"judge",return_value=1):
                    result = benchmark.experiment(Path("input.in"),"retry",config,Path("judge.exe"),area=Path("area"))
                self.assertEqual(result["raw_objective"],100)
                self.assertEqual(benchmark.digest(output),original["solution_sha256"])
            finally:
                os.chdir(previous_cwd)

    def test_zip_escape_and_alias_rejected_before_extraction(self):
        for name in ("../escape.in", "/escape.in", "a/../../escape.in", "C:/escape.in",
                     "..\\escape.in", "a.in:stream", "trailing./x.in", "CON.in", "a/NUL"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                with zipfile.ZipFile(root/"archive.zip", "w") as archive:
                    archive.writestr("good.in", "not extracted")
                    archive.writestr(name, "unsafe")
                with self.assertRaises(ValueError):
                    inventory.inventory(root/"archive.zip", root/"extracted", root/"report")
                self.assertFalse((root/"extracted/good.in").exists())

    def test_case_collision_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with zipfile.ZipFile(root/"archive.zip", "w") as archive:
                archive.writestr("A.in", "one")
                archive.writestr("a.in", "two")
            with self.assertRaises(ValueError):
                inventory.inventory(root/"archive.zip", root/"extracted", root/"report")

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with zipfile.ZipFile(root/"archive.zip", "w") as archive:
                link = zipfile.ZipInfo("link.in")
                link.create_system = 3
                link.external_attr = 0o120777 << 16
                archive.writestr(link, "../outside")
            with self.assertRaises(ValueError):
                inventory.inventory(root/"archive.zip", root/"extracted", root/"report")

    def test_invalid_count_trailing_and_duplicate_link(self):
        valid = "2 1 1 1 100\n50 50\n1000 1\n0 50\n0 0 1\n"
        cases = [valid.replace("0 0 1", "0 0 10001"), valid+"99\n",
                 valid.replace("1000 1\n0 50", "1000 2\n0 50\n0 50")]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/"test.in"
            path.write_text(valid, encoding="ascii")
            self.assertEqual(inventory.inspect_input(path)[1], [])
            for content in cases:
                path.write_text(content, encoding="ascii")
                self.assertTrue(inventory.inspect_input(path)[1])

    def test_duplicates_and_natural_order(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            content = "1 1 1 1 100\n50\n1000 1\n0 50\n0 0 1\n"
            with zipfile.ZipFile(root/"archive.zip", "w") as archive:
                archive.writestr("nested/test10.in", content)
                archive.writestr("nested/test2.in", content)
                archive.writestr("readme.md", "unsupported")
            with contextlib.redirect_stdout(io.StringIO()):
                inventory.inventory(root/"archive.zip", root/"extracted", root/"report")
            with (root/"report/instance-inventory.csv").open(encoding="utf-8", newline="") as stream:
                rows = list(__import__("csv").DictReader(stream))
            self.assertEqual([r["filename"] for r in rows], ["test2.in","test10.in","readme.md"])
            self.assertEqual([r["status"] for r in rows], ["valid","duplicate","unsupported"])

    def test_competitive_reproducible_and_valid(self):
        with tempfile.TemporaryDirectory() as folder:
            for family in ("scarcity","packing","overlap","mixed"):
                for seed in (17,101):
                    path = Path(folder)/"instance.in"
                    first = generator.generate_competitive(path, family, seed, 4)
                    second = generator.generate_competitive(path, family, seed, 4)
                    self.assertEqual(first["sha256"], second["sha256"])
                    values, errors = inventory.inspect_input(path)
                    self.assertEqual(errors, [])
                    self.assertTrue(values["judge_integer_safe"])


if __name__ == "__main__":
    unittest.main()
