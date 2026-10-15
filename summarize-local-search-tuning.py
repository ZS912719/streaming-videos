#!/usr/bin/env python3
"""Summarize the archived per-move traces of the winning tuned searches."""

import csv
from pathlib import Path


ROOT = Path(__file__).resolve().parent
TRACE_ROOT = ROOT / "experiments" / "local-search-winning-traces"
OUTPUT = ROOT / "experiments" / "local-search-optimization-convergence.csv"


def main():
    summary = []
    for path in sorted(TRACE_ROOT.glob("*.csv")):
        with path.open(newline="", encoding="utf-8-sig") as source:
            rows = list(csv.DictReader(source))
        initial = int(rows[0]["cumulative_saved_latency"])
        passes = sorted({int(row["pass"]) for row in rows if int(row["pass"]) > 0})
        for pass_number in passes:
            selected = [row for row in rows if int(row["pass"]) == pass_number]
            final = selected[-1]
            summary.append({
                "dataset": path.stem,
                "pass": pass_number,
                "accepted_moves_in_pass": len(selected),
                "cumulative_accepted_moves": final["accepted_move"],
                "cumulative_saved_latency_gain":
                    int(final["cumulative_saved_latency"]) - initial,
                "official_score_equivalent": final["score_equivalent"],
                "elapsed_ms": final["elapsed_ms"],
            })
    with OUTPUT.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=list(summary[0]))
        writer.writeheader()
        writer.writerows(summary)
    print(f"Wrote {len(summary)} per-pass convergence rows to {OUTPUT}")


if __name__ == "__main__":
    main()
