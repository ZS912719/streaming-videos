#!/usr/bin/env python3
"""Resumable, officially judged experiments using the existing solver implementations."""
import argparse
import csv
import hashlib
import json
import platform
import os
import re
import shutil
import statistics
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPORT = Path("experiments/promo-benchmark")

# Declared before looking at pilot scores. No dataset-specific parameter choices.
CANDIDATES = {
    "dynamic-repack": dict(constructor="dynamic", weight=1, ls_passes=10, repack_passes=5,
                           candidates=64, exchange_rounds=0, cycles=1, cache_order="forward"),
    "static-repack": dict(constructor="static", weight=1, ls_passes=10, repack_passes=5,
                          candidates=64, exchange_rounds=0, cycles=1, cache_order="forward"),
    "zero-repack": dict(constructor="dynamic", weight=0, ls_passes=10, repack_passes=5,
                        candidates=64, exchange_rounds=0, cycles=1, cache_order="forward"),
    "dynamic-repack-exchange": dict(constructor="dynamic", weight=1, ls_passes=10, repack_passes=5,
                                    candidates=64, exchange_rounds=5, cycles=1, cache_order="forward"),
}
PILOT = ["8_2_8_2.in", "6970_311_100000_69.in", "dense2.in",
         "medium_mixed_lambda.in", "regional_instance.in"]


def read_csv(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path, rows, fields=None, delimiter=","):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = fields or list(rows[0])
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter=delimiter, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def judge(input_path, output_path, executable):
    result = subprocess.run([str(Path(executable).resolve()), str(input_path), str(output_path)],
                            text=True, capture_output=True, timeout=30)
    match = re.fullmatch(r"\s*Score\s*=\s*(-?\d+)\s*", result.stdout)
    if result.returncode or not match:
        raise RuntimeError("Judge failed: " + result.stdout + result.stderr)
    return int(match[1])


def experiment(input_path, label, config, judge_path, timeout=180, area=REPORT):
    """Keep every validated stage and never replace a superior archived checkpoint."""
    area = Path(area)
    signature = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()[:12]
    input_hash = digest(input_path)
    key = f"{input_hash[:12]}-{label}-{signature}"
    saved = area / "runs" / f"{key}.json"
    previous = None
    if saved.exists():
        previous = json.loads(saved.read_text())
        if previous["status"] in ("completed", "partial_timeout") and previous.get("solution"):
            output = Path(previous["solution"])
            if digest(output) != previous["solution_sha256"]:
                raise RuntimeError("Archived solution changed: " + str(output))
            if judge(input_path, output, judge_path) != previous["official_score"]:
                raise RuntimeError("Archived solution judge mismatch")
            print(f"Resumed {Path(input_path).name} {label}: {previous['official_score']}", flush=True)
            return previous
    work = Path("tmp/promo-runs") / key
    # Failed/incomplete attempts are retained rather than overwritten on resume.
    if work.exists():
        work = work.with_name(work.name + f"-retry-{time.time_ns()}")
    work.mkdir(parents=True)
    command = ["java", "-Xmx4g", "-cp", "out", "com.hashcode.streaming.experiments.PromotionPipelineRunner",
               str(input_path), str(work), str(config["constructor"]), str(config["weight"]),
               str(config["ls_passes"]), str(config["repack_passes"]), str(config["candidates"]),
               str(config["exchange_rounds"]), str(config["cycles"]), str(config["cache_order"])]
    start = time.perf_counter()
    status, message = "completed", ""
    with (work / "process.log").open("w", encoding="utf-8") as log:
        try:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            if process.returncode:
                status, message = "failed", f"Java exit {process.returncode}; see temporary process log"
        except subprocess.TimeoutExpired:
            status, message = "partial_timeout", f"External safety limit {timeout}s; last completed stage retained"
    wall = time.perf_counter()-start
    stages = read_csv(work / "stages.csv") if (work / "stages.csv").exists() else []
    validated = []
    for stage in stages:
        actual = judge(input_path, work / stage["solution"], judge_path)
        if actual != int(stage["internal_score"]):
            # Do not publish an inconsistent score or lose earlier validated stages.
            raise RuntimeError(f"Official/internal mismatch: {Path(input_path).name} stage {stage['stage']}: {actual}/{stage['internal_score']}")
        stage["official_score"] = actual
        validated.append(stage)
    result = dict(instance=Path(input_path).name, input_path=Path(input_path).as_posix(), input_sha256=input_hash, configuration=label,
                  parameters=config, status=status if validated else "failed", message=message,
                  official_score=None, raw_objective=None, construction_ms=0, search_ms=0,
                  algorithm_ms=0, wall_seconds=round(wall, 6), solution="", solution_sha256="",
                  stages=validated, command=command, judge_sha256=digest(judge_path))
    if validated:
        best = max(validated, key=lambda s: (int(s["raw_objective"]), int(s["stage"])))
        if previous is not None and previous.get("raw_objective") is not None \
                and previous["raw_objective"] > int(best["raw_objective"]):
            # Compare BEFORE copying: equal rounded scores can hide a worse raw objective.
            return previous
        solution = area / "solutions" / f"{key}-{best['official_score']}.txt"
        solution.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work / best["solution"], solution)
        result.update(official_score=int(best["official_score"]), raw_objective=int(best["raw_objective"]),
                      construction_ms=float(validated[0]["algorithm_ms"]),
                      search_ms=round(sum(float(s["algorithm_ms"]) for s in validated[1:]), 3),
                      algorithm_ms=round(sum(float(s["algorithm_ms"]) for s in validated), 3),
                      solution=solution.as_posix(), solution_sha256=digest(solution))
    saved.parent.mkdir(parents=True, exist_ok=True)
    if previous is not None and previous.get("raw_objective") is not None and result["raw_objective"] is None:
        return previous
    temporary = saved.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(result, indent=2)+"\n", encoding="utf-8")
    temporary.replace(saved)
    print(f"{Path(input_path).name} {label}: score={result['official_score']} "
          f"algorithm={result['algorithm_ms']/1000:.3f}s wall={wall:.3f}s {result['status']}", flush=True)
    return result


def eligible(row):
    return row["status"] == "valid" and row["judge_integer_safe"] == "True"


def pilot(args):
    inventory = read_csv(REPORT / "instance-inventory.csv")
    rows = []
    for item in inventory:
        if item["relative_path"] not in PILOT or not eligible(item):
            continue
        for label, configuration in CANDIDATES.items():
            result = experiment(args.inputs / item["relative_path"], label, configuration,
                                args.judge, args.timeout, REPORT / "pilot")
            rows.append({k: result[k] for k in ("instance", "configuration", "official_score", "status",
                                               "construction_ms", "search_ms", "algorithm_ms", "wall_seconds")})
            write_csv(REPORT / "pilot-results.csv", rows)


def freeze(args):
    path = REPORT / "configuration.json"
    if path.exists():
        raise ValueError("Configuration already frozen; do not overwrite a final benchmark protocol")
    rows = read_csv(REPORT / "pilot-results.csv")
    if len(rows) != len(PILOT)*len(CANDIDATES) or any(r["status"] != "completed" for r in rows):
        raise ValueError("Pilot matrix incomplete")
    best = {name: max(int(r["official_score"]) for r in rows if r["instance"] == name) for name in PILOT}
    ranking = []
    for name in CANDIDATES:
        subset = [r for r in rows if r["configuration"] == name]
        loss = statistics.mean((best[r["instance"]]-int(r["official_score"]))/max(1,best[r["instance"]]) for r in subset)
        ranking.append((loss, sum(float(r["algorithm_ms"]) for r in subset), name))
    ranking.sort()
    selected = ranking[0][2]
    # A complexity tie is resolved by measured total pilot time, not a new parameter search.
    configuration = dict(name=selected, parameters=CANDIDATES[selected], pilot_instances=PILOT,
                         selection="smallest mean relative pilot score deficit; total pilot algorithm time breaks exact ties",
                         pilot_ranking=[dict(name=n, mean_relative_deficit=l, total_algorithm_ms=t) for l,t,n in ranking],
                         external_safety_seconds=args.timeout, heap="4g", frozen_before_final_evaluation=True,
                         resource_policy=dict(repack_max_estimated_cells=3000000000,
                                              exchange_max_transfer_slots=50000000,
                                              exchange_pairs=1000, exchange_videos_per_cache=64,
                                              exchange_seed=20170317),
                         input_validation="official constraints; CRLF accepted",
                         environment=dict(os=platform.platform(), python=platform.python_version(),
                                          java=subprocess.run(["java","-version"], capture_output=True,text=True).stderr.strip()),
                         source_revision=subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip())
    path.write_text(json.dumps(configuration, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(configuration, indent=2), flush=True)


def benchmark(args):
    configuration = json.loads((REPORT / "configuration.json").read_text())
    rows, detail = [], []
    for item in read_csv(REPORT / "instance-inventory.csv"):
        status = item["status"]
        row = {"Instance": item["relative_path"], "Official score": "", "Runtime (s)": "",
               "Configuration": configuration["name"], "Status": status}
        overflow = item.get("judge_integer_safe") == "False"
        selected_judge = args.wide_judge if overflow else args.judge
        if item["status"] == "valid" and (not overflow or selected_judge.exists()):
            try:
                result = experiment(args.inputs / item["relative_path"], configuration["name"],
                                    configuration["parameters"], selected_judge, configuration["external_safety_seconds"])
                row.update({"Official score": result["official_score"], "Runtime (s)": round(result["algorithm_ms"]/1000, 6),
                            "Status": result["status"] + ("_wide_judge" if overflow else "")})
                detail.append({"order": item["order"], "judge_variant": "wide_integer" if overflow else "original",
                               **{k:v for k,v in result.items() if k not in ("stages","parameters","command")}})
            except Exception as error:
                row["Status"] = "failed: " + str(error)
        elif status == "valid":
            row["Status"] = "judge_integer_overflow"
        rows.append(row)
        # Export after EVERY input; a later failure cannot erase completed results.
        write_csv(REPORT / "promo-scores.csv", rows)
        write_csv(REPORT / "promo-scores.tsv", rows, delimiter="\t")
        if detail:
            write_csv(REPORT / "promo-details.csv", detail)
        scored = [r for r in rows if isinstance(r["Official score"], int)]
        (REPORT / "promo-scores-only.txt").write_text("".join(str(r["Official score"])+"\n" for r in scored), encoding="ascii")
        write_csv(REPORT / "scores-only-order.csv", [{"score_line":i+1,"instance":r["Instance"]} for i,r in enumerate(scored)], ["score_line","instance"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["pilot","freeze","run"])
    parser.add_argument("--inputs", type=Path, default=Path("tmp/promo-inputs"))
    parser.add_argument("--judge", type=Path, default=Path("tmp/judgeHashCode2017.exe"))
    parser.add_argument("--wide-judge", type=Path, default=Path("tmp/judgeHashCode2017-wide.exe"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    os.chdir(ROOT)
    {"pilot":pilot, "freeze":freeze, "run":benchmark}[args.action](args)


if __name__ == "__main__":
    main()
