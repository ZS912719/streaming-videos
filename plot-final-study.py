#!/usr/bin/env python3
"""Optional figure generation with Matplotlib; solver/replay scripts use no third-party libraries."""
from pathlib import Path
import csv
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent

def read(path):
    with path.open(newline="", encoding="utf-8-sig") as source:
        return list(csv.DictReader(source))

def requests(dataset):
    with (ROOT / "examples" / f"{dataset}.in").open() as source:
        _, endpoints, descriptions, _, _ = map(int, next(source).split())
        next(source)
        for _ in range(endpoints):
            _, links = map(int, next(source).split())
            for _ in range(links): next(source)
        return sum(int(next(source).split()[2]) for _ in range(descriptions))

def main():
    ledger = read(ROOT / "experiments" / "final-study-ledger.csv")
    by_id = {row["run_id"]: row for row in ledger}
    by_path = {row["output_path"]: row for row in ledger}
    def chain(row):
        parent = by_path.get(row["start_solution"]) if row["mode"] != "dynamic" else None
        return (chain(parent) if parent else []) + [row]
    figure, axes = plt.subplots(1, 2, figsize=(11, 4), constrained_layout=True)
    colors = {"canonical": "#667788", "indexed-static": "#db8525", "dyn1": "#267b73"}
    labels = {"canonical": "Zero regret control", "indexed-static": "Fixed initial regret", "dyn1": "Dynamic regret (w=1)"}
    for axis, dataset, short, limits in zip(axes,
            ("videos_worth_spreading", "kittens"), ("worth", "kittens"),
            ((608000, 611500), (1021600, 1024200))):
        total = requests(dataset)
        for variant in colors:
            target = by_id[f"shared-{short}-{variant}-c1-s4-local"]
            elapsed = 0.0
            points = []
            for stage in chain(target):
                for point in read(ROOT / stage["trace_path"]):
                    raw = int(point.get("raw_objective") or point.get("cumulative_saved_latency"))
                    points.append((elapsed + float(point["elapsed_ms"]) / 1000, raw * 1000 // total))
                elapsed += float(stage["algorithm_ms"]) / 1000
                points.append((elapsed, int(stage["official_score"])))
            axis.step([x for x, _ in points], [y for _, y in points], where="post",
                      label=labels[variant], color=colors[variant], linewidth=1.8)
        axis.set(title=dataset, xlabel="Cumulative algorithm time (s)", ylabel="Official score equivalent", ylim=limits)
        axis.ticklabel_format(axis="y", style="plain", useOffset=False)
        axis.grid(alpha=0.2)
    axes[0].legend(loc="lower right", fontsize=8)
    figure.suptitle("Matched search settings: local repair → packing → exchanges → local repair", fontsize=11)
    destination = ROOT / "experiments" / "figures"
    destination.mkdir(exist_ok=True)
    figure.savefig(destination / "final-score-time.png", dpi=180)
    plt.close(figure)
    print(destination / "final-score-time.png")

if __name__ == "__main__":
    main()
