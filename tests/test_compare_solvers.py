"""Integration checks: python -m unittest discover -s tests -v."""

import csv
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("compare_solvers", ROOT / "compare-solvers.py")
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)


class WorkerTimeoutTest(unittest.TestCase):
    def test_timeout_has_no_score_and_discards_partial_output(self):
        (ROOT / "tmp").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "tmp") as temporary:
            directory = Path(temporary)
            (directory / "greedy.out").write_text("partial", encoding="ascii")
            (directory / "greedy.metrics.csv").write_text("partial", encoding="ascii")
            with patch.object(COMPARISON.subprocess, "run",
                              side_effect=subprocess.TimeoutExpired("java", 1)):
                row = COMPARISON.run_solver(("java", directory, directory / "input.in",
                                              directory, "greedy", "64m", 1))
            self.assertEqual("timeout", row["status"])
            self.assertEqual("", row["score"])
            self.assertFalse((directory / "greedy.out").exists())
            self.assertFalse((directory / "greedy.metrics.csv").exists())
            self.assertTrue((directory / "greedy.log").exists())


class SolverComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT / "tmp").mkdir(exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(prefix="comparison-test-", dir=ROOT / "tmp")
        cls.directory = Path(cls.temporary.name)
        cls.inputs = []
        for folder, example in [("first with spaces", "local-search.in"),
                                ("second", "opportunity-cost.in")]:
            parent = cls.directory / folder
            parent.mkdir()
            target = parent / "same name.in"
            shutil.copyfile(ROOT / "examples" / example, target)
            cls.inputs.append(target)
        malformed = cls.directory / "malformed.in"
        malformed.write_text("", encoding="ascii")
        cls.inputs.append(malformed)
        cls.output = cls.directory / "results with spaces"
        cls.process = subprocess.run(
            [sys.executable, str(ROOT / "compare-solvers.py")]
            + [str(path) for path in cls.inputs]
            + ["--output-dir", str(cls.output), "--jobs", "2", "--heap", "64m"],
            cwd=cls.directory, capture_output=True, text=True, timeout=120)
        summary = cls.output / "comparison.csv"
        if not summary.is_file():
            raise AssertionError(cls.process.stdout + cls.process.stderr)
        with summary.open(newline="", encoding="utf-8-sig") as stream:
            cls.rows = list(csv.DictReader(stream))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_runs_all_registered_strategies_and_keeps_same_named_inputs_separate(self):
        self.assertEqual(15, len(self.rows))
        self.assertEqual({"baseline", "greedy", "ocag", "local-search", "ocag-ls"},
                         {row["algorithm"] for row in self.rows})
        paths = [row["solution_path"] for row in self.rows if row["status"] == "complete"]
        self.assertEqual(10, len(set(paths)))
        self.assertTrue(all(Path(path).is_file() for path in paths))

    def test_known_scores_ranks_and_greedy_differences(self):
        local = {row["algorithm"]: row for row in self.rows[:5]}
        opportunity = {row["algorithm"]: row for row in self.rows[5:10]}
        self.assertEqual(370588, int(local["greedy"]["score"]))
        self.assertEqual(529411, int(local["local-search"]["score"]))
        self.assertEqual(158823, int(local["local-search"]["delta_vs_greedy"]))
        self.assertEqual(549500, int(opportunity["greedy"]["score"]))
        self.assertEqual(925000, int(opportunity["ocag"]["score"]))
        self.assertEqual(opportunity["ocag"]["rank"], opportunity["ocag-ls"]["rank"])
        self.assertEqual("1", opportunity["ocag"]["rank"])
        for row in self.rows[:10]:
            self.assertEqual("complete", row["status"])
            self.assertGreaterEqual(float(row["solve_ms"]), 0)
            self.assertGreaterEqual(float(row["worker_seconds"]), float(row["solve_ms"]) / 1000 - .001)

    def test_failure_does_not_stop_other_workers_or_publish_invalid_solutions(self):
        self.assertEqual(1, self.process.returncode)
        for row in self.rows[10:]:
            self.assertEqual("failed", row["status"])
            self.assertEqual("", row["score"])
            self.assertEqual("", row["solution_path"])
            self.assertTrue(row["error"])
            self.assertTrue(Path(row["log_path"]).is_file())
        self.assertFalse(list((self.output / "dataset-003").glob("*.out")))

    def test_existing_output_is_preserved(self):
        summary = self.output / "comparison.csv"
        before = summary.read_bytes()
        process = subprocess.run(
            [sys.executable, str(ROOT / "compare-solvers.py"), str(self.inputs[0]),
             "--output-dir", str(self.output)], capture_output=True, text=True)
        self.assertEqual(2, process.returncode)
        self.assertEqual(before, summary.read_bytes())


if __name__ == "__main__":
    unittest.main()
