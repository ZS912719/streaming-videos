#!/usr/bin/env python3
"""Freeze the selected portable run graph and reference hashes for exact replay."""
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXP = ROOT / "experiments"


def main():
    with (EXP / "final-study-ledger.csv").open(newline="", encoding="utf-8-sig") as source:
        ledger = list(csv.DictReader(source))
    by_id = {row["run_id"]: row for row in ledger}
    by_path = {row["output_path"]: row for row in ledger}
    records = {
        "me_at_the_zoo": "efficient-zoo-final",
        "videos_worth_spreading": "worth-final-repack-c2-s4-local",
        "trending_today": "dyn-trending_today-w0",
        "kittens": "kittens-final-repack-c2-s4-local",
    }
    efficient = dict(records, videos_worth_spreading="fair-repack-videos_worth_spreading-dynamic-local",
                     kittens="fair-repack-kittens-dynamic-local")
    shared = {"me_at_the_zoo": "shared-me_at_the_zoo-dyn1-c1-s4-local",
              "videos_worth_spreading": "shared-worth-dyn1-c1-s4-local",
              "trending_today": "shared-trending_today-dyn1-c1-s4-local",
              "kittens": "shared-kittens-dyn1-c1-s4-local"}
    selected = {}

    def add(run_id):
        row = by_id[run_id]
        if row["mode"] != "dynamic" and row["start_solution"] in by_path:
            add(by_path[row["start_solution"]]["run_id"])
        selected[run_id] = row

    for group in (records, efficient, shared):
        for run_id in group.values():
            add(run_id)
    config = {
        "study_date": "2026-10-15", "branch": "experiment/local-search-tuning",
        "java": "Oracle Java 21.0.10; release 17; Xmx4g",
        "host": "same Windows host as experiment-config.json; 12 logical processors",
        "exchange_pair_seed": 20170317,
        "dynamic_formula": "(current gain + weight * max(0, current gain - best feasible current alternative gain)) / video size",
        "dynamic_ties": "priority descending; raw gain descending; video ID ascending; cache ID ascending",
        "construction_controls": "indexed static regret and zero-regret canonical greedy use the same index and tie rules",
        "record_targets": records, "efficient_targets": efficient, "shared_targets": shared,
        "reference_hashes_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                    for path in sorted((EXP / "final-study-references").glob("*.txt"))},
        "runs": list(selected.values()),
    }
    with (EXP / "final-ocag-ls-configurations.json").open("w", encoding="utf-8") as target:
        json.dump(config, target, indent=2)
        target.write("\n")
    print(f"Saved {len(selected)} reproducible stages for records, efficient and shared targets")


if __name__ == "__main__":
    main()
