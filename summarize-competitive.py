#!/usr/bin/env python3
"""Compact stage attribution and held-out checks, without discarding negative results."""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("benchmark", ROOT/"promo-benchmark.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
area = Path("experiments/competitive-instance")
config = json.loads((area/"competitive-instance-config.json").read_text())
stages = []
for file in sorted((area/"runs").glob("*.json")):
    result = json.loads(file.read_text())
    if result["instance"] != config["primary"]:
        continue
    previous = 0
    for row in result["stages"]:
        stages.append(dict(algorithm=result["configuration"],stage=row["stage"],operation=row["operation"],
                           official_score=row["official_score"],score_gain=int(row["official_score"])-previous,
                           raw_objective=row["raw_objective"],algorithm_ms=row["algorithm_ms"],
                           passes=row["passes"],accepted=row["accepted"],termination=row["termination"]))
        previous = int(row["official_score"])
bench.write_csv(area/"competitive-stage-results.csv",stages)

rows = bench.read_csv(area/"validation-results.csv")
summary = []
for instance in sorted({r["candidate"] for r in rows}):
    subset = [r for r in rows if r["candidate"] == instance]
    own = int(next(r["official_score"] for r in subset if r["algorithm"] == "ours-dynamic"))
    control = max((r for r in subset if r["algorithm"] not in ("ours-dynamic","dynamic-ordinary")),
                  key=lambda r:int(r["official_score"]))
    best = int(control["official_score"])
    summary.append(dict(instance=instance,our_score=own,strongest_reference=control["algorithm"],
                        reference_score=best,gap=own-best,relative_gap_percent=round(100*(own-best)/best,6)))
bench.write_csv(area/"robustness-summary.csv",summary)
print(summary)
