#!/usr/bin/env python3
"""Generate compact results and exact score/time checkpoints from the experiment ledger."""
import csv
import json
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXP = ROOT / "experiments"
PREVIOUS = dict(me_at_the_zoo=507906, videos_worth_spreading=610023,
                trending_today=499857, kittens=1022005)
ORIGINAL = dict(me_at_the_zoo=507906, videos_worth_spreading=608302,
                trending_today=499794, kittens=1021681)


def read(path):
    with path.open(newline="", encoding="utf-8-sig") as source:
        return list(csv.DictReader(source))


def write(name, rows):
    with (EXP / name).open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def total_requests(dataset):
    with (ROOT / "examples" / f"{dataset}.in").open() as source:
        _, endpoints, descriptions, _, _ = map(int, next(source).split())
        next(source)
        for _ in range(endpoints):
            _, links = map(int, next(source).split())
            for _ in range(links):
                next(source)
        return sum(int(next(source).split()[2]) for _ in range(descriptions))


def main():
    ledger = read(EXP / "final-study-ledger.csv")
    by_id = {row["run_id"]: row for row in ledger}
    by_output = {row["output_path"].replace("\\", "/"): row for row in ledger}
    old_runtime = {(row["dataset"], row["algorithm"]): float(row["median_algorithm_ms"])
                   for row in read(EXP / "runtime-summary.csv")}

    def chain(row):
        if row["mode"] == "dynamic":
            return [row]
        parent = by_output.get(row["start_solution"].replace("\\", "/"))
        return (chain(parent) if parent else []) + [row]

    def source_kind(row):
        root = chain(row)[0]
        if root["mode"] == "dynamic":
            if float(root["weight"]) == 0:
                return "canonical_greedy"
            return "indexed_static" if root["regret_mode"] == "static" else "dynamic_regret"
        return "saved_record_continuation" if "final-study-references" in root["start_solution"] else "original_greedy"

    def time(row):
        construction = old_runtime[(row["dataset"], "greedy")] if source_kind(row) == "original_greedy" else 0.0
        return construction + sum(float(stage["algorithm_ms"]) for stage in chain(row))

    dynamic = []
    seen = set()
    for row in ledger:
        if row["mode"] != "dynamic":
            continue
        key = row["dataset"], row["regret_mode"], row["weight"]
        if key in seen:
            continue
        seen.add(key)
        group = [r for r in ledger if r["mode"] == "dynamic" and
                 (r["dataset"], r["regret_mode"], r["weight"]) == key]
        times = [float(r["algorithm_ms"]) for r in group]
        if len({r["official_score"] for r in group}) != 1:
            raise ValueError(f"Nondeterministic construction scores: {key}")
        trace = read(ROOT / row["trace_path"])
        control = read(ROOT / by_id[f"dyn-{row['dataset']}-w0"]["trace_path"])
        selected = [(r["cache_a"], r["video_a"]) for r in trace]
        baseline = [(r["cache_a"], r["video_a"]) for r in control]
        changed = sum(a != b for a, b in zip(selected, baseline)) + abs(len(selected) - len(baseline))
        pair_union = set(selected) | set(baseline)
        dynamic.append({
            "dataset": row["dataset"], "regret_mode": row["regret_mode"], "weight": row["weight"],
            "official_score": row["official_score"], "runs": len(group),
            "median_ms": f"{statistics.median(times):.3f}", "min_ms": f"{min(times):.3f}", "max_ms": f"{max(times):.3f}",
            "difference_vs_original_greedy": int(row["official_score"]) - ORIGINAL[row["dataset"]],
            "regret_updates": row["regret_updates"], "alternatives_lost": row["infeasible_alternatives"],
            "selected_regret_changed_from_initial": row["changed_selected_regrets"],
            "positive_regret_placements": row["positive_regret_placements"],
            "candidate_evaluations": row["evaluated"], "gain_array_bytes_only": row["estimated_array_bytes"],
            "changed_steps_vs_zero_control": changed,
            "placement_jaccard_vs_zero_control": f"{len(set(selected) & set(baseline)) / len(pair_union) if pair_union else 1:.6f}",
        })
    write("dynamic-ocag-results.csv", dynamic)

    advanced = []
    for row in ledger:
        if row["mode"] == "dynamic":
            continue
        parent = by_output.get(row["start_solution"].replace("\\", "/"))
        initial = int(parent["official_score"]) if parent else (
            PREVIOUS[row["dataset"]] if "final-study-references" in row["start_solution"] else ORIGINAL[row["dataset"]])
        advanced.append({key: row[key] for key in ("run_id", "dataset", "mode", "official_score", "algorithm_ms", "index_ms", "scan_ms", "update_ms",
                        "accepted", "evaluated", "feasible", "positive", "rounded_improvements", "executed_passes", "executed_rounds", "termination")} |
                        {"initial_score": initial, "score_gain": int(row["official_score"]) - initial})
    write("advanced-local-search-results.csv", advanced)

    joint = []
    targets = []
    for row in ledger:
        if (re.search(r"-c\d+-s[34]-local$", row["run_id"])
                or row["run_id"] in ("efficient-kittens-final",)
                or (row["run_id"].startswith("fair-repack-") and row["run_id"].endswith("-local"))):
            stages = chain(row)
            joint.append(dict(run_id=row["run_id"], dataset=row["dataset"], source=source_kind(row),
                              stages=len(stages), official_score=row["official_score"], cumulative_algorithm_ms=f"{time(row):.3f}"))
            if stages[0]["mode"] == "dynamic":
                targets.append(row)
    write("joint-optimization-results.csv", joint)

    checkpoints = []
    totals = {dataset: total_requests(dataset) for dataset in PREVIOUS}
    for row in targets:
        offset = 0.0
        events = [(0.0, 0)]
        for stage in chain(row):
            for point in read(ROOT / stage["trace_path"]):
                raw = int(point.get("raw_objective") or point.get("cumulative_saved_latency"))
                score = raw * 1000 // totals[row["dataset"]]
                events.append((offset + float(point["elapsed_ms"]), score))
            offset += float(stage["algorithm_ms"])
            events.append((offset, int(stage["official_score"])))
        for seconds in (5, 15, 30, 60, 120, 300):
            if offset < seconds * 1000:
                continue
            score = max(s for elapsed, s in events if elapsed <= seconds * 1000)
            checkpoints.append(dict(run_id=row["run_id"], dataset=row["dataset"], source=source_kind(row),
                                    budget_seconds=seconds, best_official_score_equivalent=score))
    write("score-time-frontier.csv", checkpoints)

    pareto = []
    for dataset in PREVIOUS:
        best = -1
        candidates = [row for row in ledger if row["dataset"] == dataset and chain(row)[0]["mode"] == "dynamic"]
        for row in sorted(candidates, key=lambda r: (time(r), -int(r["official_score"]))):
            score = int(row["official_score"])
            if score > best:
                pareto.append(dict(dataset=dataset, run_id=row["run_id"], source=source_kind(row),
                                   official_score=score, observed_algorithm_ms=f"{time(row):.3f}"))
                best = score
    write("score-runtime-pareto.csv", pareto)

    final = []
    for dataset, previous in PREVIOUS.items():
        group = [row for row in ledger if row["dataset"] == dataset]
        winner = min(group, key=lambda row: (-int(row["official_score"]), time(row)))
        preferred = {"me_at_the_zoo": "efficient-zoo-final", "trending_today": "dyn-trending_today-w0"}
        if dataset in preferred and int(by_id[preferred[dataset]]["official_score"]) == int(winner["official_score"]):
            winner = by_id[preferred[dataset]]
        static_rows = [row for row in group if row["mode"] != "dynamic" and source_kind(row) != "dynamic_regret"]
        dynamic_rows = [row for row in group if row["mode"] == "dynamic" and row["regret_mode"] == "dynamic" and float(row["weight"]) > 0]
        combined_rows = [row for row in group if row["mode"] != "dynamic" and source_kind(row) == "dynamic_regret"]
        score = int(winner["official_score"])
        final.append(dict(dataset=dataset, previous_record=previous,
                          best_ls=max(int(r["official_score"]) for r in static_rows),
                          best_dynamic=max(int(r["official_score"]) for r in dynamic_rows),
                          best_combined=max(int(r["official_score"]) for r in combined_rows),
                          final_best=max(previous, score), gain=max(0, score - previous),
                          gain_percent=f"{100 * (score - previous) / previous:.6f}",
                          winning_run=winner["run_id"], source=source_kind(winner),
                          cumulative_algorithm_ms=f"{time(winner):.3f}"))
    write("final-ocag-ls-results.csv", final)
    configuration = EXP / "final-ocag-ls-configurations.json"
    if configuration.exists():
        with configuration.open(encoding="utf-8") as source:
            settings = json.load(source)
        efficient = []
        for dataset, run_id in settings["efficient_targets"].items():
            row = by_id[run_id]
            efficient.append(dict(dataset=dataset, official_score=row["official_score"],
                                  selected_run=run_id, cumulative_algorithm_ms=f"{time(row):.3f}"))
        write("final-efficient-results.csv", efficient)
    print(json.dumps(final, indent=2))


if __name__ == "__main__":
    main()
