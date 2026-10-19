#!/usr/bin/env python3
"""Reconcile archived benchmarks, export stage gains and test judge-width equivalence."""
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark", Path(__file__).with_name("promo-benchmark.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
area = Path("experiments/promo-benchmark")
rows, judges = [], []
inventory = {r["sha256"]:r for r in bench.read_csv(area/"instance-inventory.csv")}
for file in sorted((area/"runs").glob("*.json")):
    result = json.loads(file.read_text())
    item = inventory[result["input_sha256"]]
    prior_score = 0
    for stage in result["stages"]:
        rows.append(dict(instance=result["instance"], stage=stage["stage"], operation=stage["operation"],
                         official_score=stage["official_score"], gain=int(stage["official_score"])-prior_score,
                         raw_objective=stage["raw_objective"], algorithm_ms=stage["algorithm_ms"],
                         passes=stage["passes"], accepted=stage["accepted"], termination=stage["termination"]))
        prior_score = int(stage["official_score"])
    input_path = Path("tmp/promo-inputs")/item["relative_path"]
    original = bench.judge(input_path, result["solution"], Path("tmp/judgeHashCode2017.exe"))
    wide = bench.judge(input_path, result["solution"], Path("tmp/judgeHashCode2017-wide.exe"))
    expected = result["raw_objective"]*1000//int(item["total_requests"])
    if wide != expected or (item["judge_integer_safe"] == "True" and original != wide):
        raise ValueError("Judge arithmetic reconciliation failed: "+result["instance"])
    judges.append(dict(instance=result["instance"], total_requests=item["total_requests"], original_judge_score=original,
                       wide_judge_score=wide, internal_score=expected, original_integer_safe=item["judge_integer_safe"],
                       solution_sha256=result["solution_sha256"]))
order = {r["filename"]:int(r["order"]) for r in inventory.values()}
rows.sort(key=lambda r:(order[r["instance"]],int(r["stage"])))
judges.sort(key=lambda r:order[r["instance"]])
bench.write_csv(area/"stage-results.csv",rows)
bench.write_csv(area/"judge-width-comparison.csv",judges)
named = bench.read_csv(area/"promo-scores.csv")
column = (area/"promo-scores-only.txt").read_text().splitlines()
assert column == [r["Official score"] for r in named if r["Official score"]]
assert len(named) == len(inventory)
assert len(column) == len(judges) == sum(r["status"] == "valid" for r in inventory.values())
assert all(r["status"] == "completed" for r in [json.loads(p.read_text()) for p in (area/"runs").glob("*.json")])
print(f"Reconciled {len(judges)} archived outputs and {len(rows)} stages; CSV/TSV/score-column alignment verified")

features = bench.read_csv(area/"instance-features.csv")
for item in features:
    stages = [r for r in rows if r["instance"] == item["filename"]]
    item["evaluation_role"] = "selection_pilot" if item["relative_path"] in bench.PILOT else "final_evaluation"
    item["previous_official_dataset"] = item["filename"] in ("me_at_the_zoo.in","videos_worth_spreading.in","trending_today.in","kittens.in")
    item["construction_score"] = stages[0]["official_score"] if stages else ""
    for operation in ("local","repack","exchange"):
        item[operation+"_score_gain"] = sum(int(r["gain"]) for r in stages if r["operation"] == operation) if stages else ""
        item[operation+"_ms"] = round(sum(float(r["algorithm_ms"]) for r in stages if r["operation"] == operation),3) if stages else ""
bench.write_csv(area/"instance-features.csv", features)
