#!/usr/bin/env python3
"""Compare every registered solver on user-provided Hash Code input files."""

import argparse
import csv
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parent
WORKER = "com.hashcode.streaming.experiments.SolverComparisonWorker"
FIELDS = ["dataset", "input_path", "algorithm", "status", "rank", "score",
          "delta_vs_greedy", "solve_ms", "worker_seconds", "non_empty_caches",
          "solution_path", "log_path", "error"]


def positive_int(value):
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def java_tool(name):
    # Prefer a configured JDK to launcher shims that may select another installation.
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        executable = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if executable.is_file():
            return str(executable)
    return shutil.which(name)


def run_solver(task):
    java, classes, input_path, dataset_dir, algorithm, heap, timeout = task
    solution = dataset_dir / (algorithm + ".out")
    metrics = dataset_dir / (algorithm + ".metrics.csv")
    log = dataset_dir / (algorithm + ".log")
    row = dict.fromkeys(FIELDS, "")
    row.update(dataset=input_path.name, input_path=str(input_path), algorithm=algorithm,
               status="failed", log_path=str(log))
    start = time.perf_counter()
    try:
        with log.open("w", encoding="utf-8") as stream:
            process = subprocess.run(
                [java, "-Xmx" + heap, "-cp", str(classes), WORKER, algorithm,
                 str(input_path), str(solution), str(metrics)],
                stdout=stream, stderr=subprocess.STDOUT, timeout=timeout, check=False)
        if process.returncode:
            raise RuntimeError("Worker exited with code %d; see log" % process.returncode)
        with metrics.open(newline="", encoding="utf-8") as stream:
            records = list(csv.DictReader(stream))
        if len(records) != 1:
            raise RuntimeError("Expected one metrics row")
        record = records[0]
        score = int(record["score"])
        solve_ms = float(record["solve_ms"])
        caches = int(record["non_empty_caches"])
        if not solution.is_file():
            raise RuntimeError("Worker did not write a solution")
        row.update(status="complete", score=score, solve_ms=solve_ms,
                   non_empty_caches=caches, solution_path=str(solution))
    except subprocess.TimeoutExpired:
        row.update(status="timeout", error="Exceeded %d seconds" % timeout)
    except Exception as error:
        row["error"] = str(error)
    finally:
        row["worker_seconds"] = round(time.perf_counter() - start, 3)
        if row["status"] != "complete":
            solution.unlink(missing_ok=True)
            metrics.unlink(missing_ok=True)
    return row


def rank_results(rows):
    successful = [row for row in rows if row["status"] == "complete"]
    scores = sorted({row["score"] for row in successful}, reverse=True)
    greedy = next((row["score"] for row in successful if row["algorithm"] == "greedy"), None)
    for row in successful:
        row["rank"] = scores.index(row["score"]) + 1
        if greedy is not None:
            row["delta_vs_greedy"] = row["score"] - greedy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="One or more .in dataset paths")
    parser.add_argument("--output-dir", type=Path, help="New directory for results (must not exist)")
    parser.add_argument("--algorithms", nargs="+", help="Only run these registered strategy names")
    parser.add_argument("--jobs", type=positive_int, default=1, help="Concurrent workers (default: 1)")
    parser.add_argument("--timeout", type=positive_int, default=300, help="Seconds per run (default: 300)")
    parser.add_argument("--heap", default="2g", help="Java heap per worker, e.g. 2g or 512m")
    args = parser.parse_args()
    if not re.fullmatch(r"[1-9][0-9]*[kKmMgG]?", args.heap):
        parser.error("--heap must be a positive JVM size, e.g. 2g or 512m")
    inputs = [path.resolve() for path in args.inputs]
    for path in inputs:
        if not path.is_file():
            parser.error("Input file does not exist: %s" % path)
    java, javac = java_tool("java"), java_tool("javac")
    if not java or not javac:
        parser.error("Java 17+ JDK required: java and javac must be on PATH")
    if args.output_dir:
        output = args.output_dir.resolve()
        if output.exists():
            parser.error("Output directory already exists; choose a new path: %s" % output)
        output.mkdir(parents=True)
    else:
        parent = ROOT / "tmp" / "comparisons"
        parent.mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("%Y%m%d-%H%M%S-"), dir=parent))
    print("Results directory: %s" % output, flush=True)
    classes = output / "classes"
    classes.mkdir()
    sources = sorted((ROOT / "src" / "main" / "java").rglob("*.java"))
    try:
        with (output / "build.log").open("w", encoding="utf-8") as stream:
            build = subprocess.run([javac, "--release", "17", "-encoding", "UTF-8",
                                    "-d", str(classes)] + [str(path) for path in sources],
                                   stdout=stream, stderr=subprocess.STDOUT, check=False, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        print("Compilation failed: %s; see %s" % (error, output / "build.log"), file=sys.stderr)
        return 2
    if build.returncode:
        print("Compilation failed; see %s" % (output / "build.log"), file=sys.stderr)
        return 2
    try:
        listing = subprocess.run([java, "-cp", str(classes), WORKER, "--list"],
                                 capture_output=True, text=True, check=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        print("Cannot list registered solvers: %s" % error, file=sys.stderr)
        return 2
    available = listing.stdout.splitlines()
    algorithms = args.algorithms or available
    if not algorithms or len(set(algorithms)) != len(algorithms) or any(
            name not in available for name in algorithms):
        parser.error("Choose distinct registered algorithms: " + ", ".join(available))
    print("Strategies: %s; workers: %d" % (", ".join(algorithms), args.jobs), flush=True)
    tasks = []
    for index, input_path in enumerate(inputs, 1):
        dataset_dir = output / ("dataset-%03d" % index)
        dataset_dir.mkdir()
        for algorithm in algorithms:
            tasks.append((java, classes, input_path, dataset_dir, algorithm, args.heap, args.timeout))
    rows = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        for row in executor.map(run_solver, tasks):
            rows.append(row)
            print("%s / %s: %s, score=%s, solve_ms=%s, worker_s=%s" % (
                row["dataset"], row["algorithm"], row["status"], row["score"],
                row["solve_ms"], row["worker_seconds"]), flush=True)
    for index in range(len(inputs)):
        rank_results(rows[index * len(algorithms):(index + 1) * len(algorithms)])
    summary = output / "comparison.csv"
    with summary.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print("Comparison CSV: %s" % summary)
    return 1 if any(row["status"] != "complete" for row in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
