#!/usr/bin/env python3
"""Fresh-JVM replay into ignored directories without changing archived measurements."""
import argparse
import importlib.util
import json
import os
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("benchmark", ROOT/"promo-benchmark.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("study",choices=["promo","competitive"])
    parser.add_argument("--instances",nargs="+")
    parser.add_argument("--judge",type=Path,default=Path("tmp/judgeHashCode2017.exe"))
    parser.add_argument("--wide-judge",type=Path,default=Path("tmp/judgeHashCode2017-wide.exe"))
    args = parser.parse_args()
    os.chdir(ROOT)
    destination = Path("tmp/promotion-replay")/f"{args.study}-{time.time_ns()}"
    rows = []
    if args.study == "promo":
        area = Path("experiments/promo-benchmark")
        inventory = {r["sha256"]:r for r in bench.read_csv(area/"instance-inventory.csv")}
        tasks = []
        for file in sorted((area/"runs").glob("*.json")):
            archived = json.loads(file.read_text())
            item = inventory[archived["input_sha256"]]
            if args.instances and item["relative_path"] not in args.instances:
                continue
            tasks.append((Path("tmp/promo-inputs")/item["relative_path"], archived["configuration"],
                          archived["parameters"],archived["official_score"],archived["solution_sha256"],
                          args.judge if item["judge_integer_safe"] == "True" else args.wide_judge))
    else:
        area = Path("experiments/competitive-instance")
        config = json.loads((area/"competitive-instance-config.json").read_text())
        tasks = [(area/"competitive_streaming_videos.in", r["algorithm"],
                  config["stress_configurations"][r["algorithm"]], int(r["official_score"]),
                  bench.digest(r["solution"]), args.judge)
                 for r in bench.read_csv(area/"competitive-instance-results.csv")
                 if r["algorithm"] != "multistart-control-portfolio"]
    if not tasks:
        parser.error("No matching archived experiments")
    for input_path, label, settings, score, solution_hash, judge in tasks:
        result = bench.experiment(input_path,label,settings,judge,180,destination)
        if result["status"] != "completed" or result["official_score"] != score or result["solution_sha256"] != solution_hash:
            raise ValueError("Fresh replay differs from archived output: "+input_path.name+" "+label)
        rows.append(dict(instance=input_path.name,configuration=label,official_score=score,
                         algorithm_ms=result["algorithm_ms"],solution_sha256=result["solution_sha256"],status="matched"))
        bench.write_csv(destination/"replay-results.csv",rows)
    print(f"Fresh replay matched scores and exact output hashes for {len(rows)} runs: {destination}")


if __name__ == "__main__":
    main()
