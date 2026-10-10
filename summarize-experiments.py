#!/usr/bin/env python3
"""Create compact summaries from the raw, reproducible experiment CSV files."""

import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent
EXPERIMENTS = ROOT / "experiments"


def read_csv(name):
    with (EXPERIMENTS / name).open(newline="", encoding="utf-8-sig") as source:
        return list(csv.DictReader(source))


def write_csv(name, fieldnames, rows):
    with (EXPERIMENTS / name).open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def median(values):
    return statistics.median(values)


def percentile(values, fraction):
    ordered = sorted(values)
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def summarize_runtime():
    groups = defaultdict(list)
    for row in read_csv("runtime-results.csv"):
        groups[(row["dataset"], row["algorithm"])].append(row)
    rows = []
    for (dataset, algorithm), group in sorted(groups.items()):
        algorithm_ms = [float(row["algorithm_ms"]) for row in group]
        construction_ms = [float(row["construction_ms"]) for row in group]
        local_ms = [float(row["local_search_ms"]) for row in group]
        rows.append({
            "dataset": dataset,
            "algorithm": algorithm,
            "official_score": group[0]["official_score"],
            "runs": len(group),
            "median_algorithm_ms": f"{median(algorithm_ms):.3f}",
            "min_algorithm_ms": f"{min(algorithm_ms):.3f}",
            "max_algorithm_ms": f"{max(algorithm_ms):.3f}",
            "median_construction_ms": f"{median(construction_ms):.3f}",
            "median_local_search_ms": f"{median(local_ms):.3f}",
        })
    write_csv("runtime-summary.csv", list(rows[0]), rows)
    return {(row["dataset"], row["algorithm"]): row for row in rows}


def summarize_local_search():
    rows = []
    for row in read_csv("local-search-results.csv"):
        initial = int(row["initial_score"])
        final = int(row["official_score"])
        gain = final - initial
        local_ms = float(row["local_search_ms"])
        construction_ms = float(row["construction_ms"])
        rows.append({
            **row,
            "absolute_score_gain": gain,
            "relative_score_gain_percent": f"{(100 * gain / initial if initial else 0):.6f}",
            "runtime_overhead_percent": f"{(100 * local_ms / construction_ms if construction_ms else 0):.3f}",
            "score_points_per_local_search_second":
                f"{(gain / (local_ms / 1000) if local_ms else 0):.3f}",
        })
    write_csv("local-search-summary.csv", list(rows[0]), rows)


def summarize_opportunity(runtime):
    rows = []
    datasets = sorted({dataset for dataset, _ in runtime})
    for dataset in datasets:
        greedy = runtime[(dataset, "greedy")]
        greedy_score = int(greedy["official_score"])
        greedy_time = float(greedy["median_algorithm_ms"])
        for algorithm in ("greedy", "ocag", "greedy-ls", "ocag-ls"):
            current = runtime[(dataset, algorithm)]
            score = int(current["official_score"])
            elapsed = float(current["median_algorithm_ms"])
            rows.append({
                "dataset": dataset,
                "algorithm": algorithm,
                "official_score": score,
                "absolute_difference_vs_greedy": score - greedy_score,
                "relative_difference_vs_greedy_percent":
                    f"{100 * (score - greedy_score) / greedy_score:.6f}",
                "median_algorithm_ms": f"{elapsed:.3f}",
                "additional_ms_vs_greedy": f"{elapsed - greedy_time:.3f}",
            })
    write_csv("opportunity-cost-summary.csv", list(rows[0]), rows)


def summarize_convergence():
    groups = defaultdict(list)
    for row in read_csv("local-search-trace.csv"):
        groups[(row["dataset"], row["algorithm"])].append(row)
    rows = []
    for (dataset, algorithm), trace in sorted(groups.items()):
        trace.sort(key=lambda row: int(row["accepted_move"]))
        initial = int(trace[0]["cumulative_saved_latency"])
        final = int(trace[-1]["cumulative_saved_latency"])
        gain = final - initial
        summary = {
            "dataset": dataset,
            "algorithm": algorithm,
            "accepted_moves": int(trace[-1]["accepted_move"]),
            "saved_latency_gain": gain,
            "moves_to_50_percent": "",
            "moves_to_90_percent": "",
            "ms_to_50_percent": "",
            "ms_to_90_percent": "",
        }
        if gain > 0:
            for fraction, label in ((0.5, "50"), (0.9, "90")):
                target = initial + fraction * gain
                point = next(row for row in trace
                             if int(row["cumulative_saved_latency"]) >= target)
                summary[f"moves_to_{label}_percent"] = point["accepted_move"]
                summary[f"ms_to_{label}_percent"] = point["elapsed_ms"]
        rows.append(summary)
    write_csv("local-search-convergence.csv", list(rows[0]), rows)


def summarize_robustness():
    raw = read_csv("robustness-results.csv")
    scenarios = defaultdict(dict)
    groups = defaultdict(list)
    for row in raw:
        key = (row["dataset"], row["model"], row["level"], row["scenario"])
        scenarios[key][row["algorithm"]] = int(row["score"])
        groups[(row["dataset"], row["model"], row["level"], row["algorithm"])].append(
            int(row["score"]))
    rows = []
    for (dataset, model, level, algorithm), scores in sorted(groups.items()):
        paired = []
        wins = 0
        ties = 0
        reference = "greedy-ls" if algorithm == "ocag-ls" else "greedy"
        for key, values in scenarios.items():
            if key[:3] != (dataset, model, level) or algorithm not in values:
                continue
            difference = values[algorithm] - values[reference]
            paired.append(difference)
            if difference > 0:
                wins += 1
            elif difference == 0:
                ties += 1
        rows.append({
            "dataset": dataset,
            "model": model,
            "level": level,
            "algorithm": algorithm,
            "scenarios": len(scores),
            "mean_score": f"{statistics.mean(scores):.3f}",
            "median_score": f"{median(scores):.3f}",
            "standard_deviation": f"{statistics.pstdev(scores):.3f}",
            "p10_score": percentile(scores, 0.10),
            "worst_score": min(scores),
            "paired_reference": reference,
            "mean_paired_difference_vs_reference": f"{statistics.mean(paired):.3f}",
            "win_rate_vs_reference_percent": f"{100 * wins / len(paired):.3f}",
            "tie_rate_vs_reference_percent": f"{100 * ties / len(paired):.3f}",
            "loss_rate_vs_reference_percent":
                f"{100 * (len(paired) - wins - ties) / len(paired):.3f}",
        })
    write_csv("robustness-summary.csv", list(rows[0]), rows)


def main():
    runtime = summarize_runtime()
    summarize_local_search()
    summarize_opportunity(runtime)
    summarize_convergence()
    summarize_robustness()
    print("Experiment summaries written to", EXPERIMENTS)


if __name__ == "__main__":
    main()
