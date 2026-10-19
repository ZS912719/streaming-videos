#!/usr/bin/env python3
"""Staged structural-instance screening with matched repair and strong controls."""
import argparse
import importlib.util
import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


bench = load("promotion", "promo-benchmark.py")
generator = load("generator", "generate-experiments.py")
inventory = load("inventory", "inventory-promotion.py")
AREA = Path("experiments/competitive-instance")


def configurations():
    shared = json.loads(Path("experiments/promo-benchmark/configuration.json").read_text())["parameters"]
    return {
        "ours-dynamic": shared,
        "static-advanced": {**shared, "constructor":"static"},
        "zero-advanced": {**shared, "weight":0},
        "greedy-ordinary": {**shared, "constructor":"greedy", "ls_passes":50, "repack_passes":0, "exchange_rounds":0},
        "greedy-advanced": {**shared, "constructor":"greedy"},
        "dynamic-ordinary": {**shared, "repack_passes":0, "exchange_rounds":0},
        "static-reverse": {**shared, "constructor":"static", "cache_order":"reverse", "cycles":2},
        "zero-reverse": {**shared, "weight":0, "cache_order":"reverse", "cycles":2},
    }


def evaluate(path, metadata, labels, args):
    values, errors = inventory.inspect_input(path)
    if errors or not values["judge_integer_safe"]:
        raise ValueError(f"Generated invalid or judge-unsafe input: {errors}")
    rows = []
    for label in labels:
        config = configurations()[label]
        result = bench.experiment(path, label, config, args.judge, args.timeout, AREA)
        rows.append(dict(candidate=path.name, family=metadata["family"], seed=metadata["seed"],
                         groups=metadata["groups"], split=metadata["split"], algorithm=label,
                         official_score=result["official_score"], algorithm_ms=result["algorithm_ms"],
                         construction_ms=result["construction_ms"], search_ms=result["search_ms"],
                         status=result["status"], solution=result["solution"],
                         input_sha256=result["input_sha256"], solution_sha256=result["solution_sha256"]))
    return rows


def screen(args):
    rows, manifest = [], []
    # Discovery and validation seeds are declared in advance. No feedback in generation.
    for family in ("scarcity", "packing", "overlap", "mixed"):
        for seed in (17, 29, 43):
            path = AREA / "candidates" / f"{family}.seed_{seed}.in"
            meta = generator.generate_competitive(path, family, seed, args.groups)
            meta["split"] = "discovery"
            manifest.append(meta)
            rows.extend(evaluate(path, meta, list(configurations())[:6], args))
            bench.write_csv(AREA / "screening-results.csv", rows)
    (AREA / "discovery-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="ascii")


def strengthen(args):
    rows = bench.read_csv(AREA / "screening-results.csv")
    groups = {}
    for row in rows:
        groups.setdefault(row["candidate"], []).append(row)
    ranked = []
    for name, subset in groups.items():
        ours = int(next(r for r in subset if r["algorithm"] == "ours-dynamic")["official_score"])
        other = max(int(r["official_score"]) for r in subset if r["algorithm"] in
                    ("static-advanced","zero-advanced","greedy-advanced"))
        ranked.append(((ours-other)/max(1,other), name))
    ranked.sort(reverse=True)
    shortlisted = ranked[:3]
    strengthened = list(rows)
    for _, name in shortlisted:
        meta = next(r for r in rows if r["candidate"] == name)
        metadata = dict(family=meta["family"], seed=int(meta["seed"]), groups=int(meta["groups"]), split="discovery")
        strengthened.extend(evaluate(AREA/"candidates"/name, metadata, ["static-reverse","zero-reverse"], args))
    bench.write_csv(AREA / "strong-results.csv", strengthened)
    shortlist = [name for _,name in shortlisted]
    (AREA / "shortlist.json").write_text(json.dumps(shortlist, indent=2)+"\n", encoding="ascii")


def validate(args):
    rows = bench.read_csv(AREA / "strong-results.csv")
    shortlist = json.loads((AREA / "shortlist.json").read_text())
    grouped = {name:[r for r in rows if r["candidate"] == name] for name in shortlist}
    ranking = []
    for name, subset in grouped.items():
        ours = int(next(r for r in subset if r["algorithm"] == "ours-dynamic")["official_score"])
        competitor = max(int(r["official_score"]) for r in subset if r["algorithm"] not in ("ours-dynamic","dynamic-ordinary"))
        ranking.append(((ours-competitor)/max(1,competitor),name))
    ranking.sort(reverse=True)
    name = ranking[0][1]
    primary = next(r for r in rows if r["candidate"] == name)
    # Family and primary seed frozen before held-out seeds are evaluated.
    config = dict(primary=name, family=primary["family"], seed=int(primary["seed"]), groups=int(primary["groups"]),
                  generator_version="competitive-v1", validation_seeds=[101,131,173],
                  selection="largest relative gap against best strengthened control among discovery shortlist",
                  configurations=configurations(), main_configuration_frozen_on="promotion pilot only")
    frozen = AREA / "competitive-instance-config.json"
    if frozen.exists() and json.loads(frozen.read_text())["primary"] != name:
        raise ValueError("Refusing to change selected instance after validation")
    frozen.write_text(json.dumps(config, indent=2)+"\n", encoding="ascii")
    validation = []
    for seed in config["validation_seeds"]:
        path = AREA / "candidates" / f"{config['family']}.seed_{seed}.in"
        metadata = generator.generate_competitive(path, config["family"], seed, config["groups"])
        metadata["split"] = "validation"
        validation.extend(evaluate(path, metadata, list(configurations()), args))
        bench.write_csv(AREA / "validation-results.csv", validation)
    selected = grouped[name]
    bench.write_csv(AREA / "competitive-instance-results.csv", selected)
    target = AREA / "competitive_streaming_videos.in"
    shutil.copyfile(AREA / "candidates" / name, target)
    our_result = next(r for r in selected if r["algorithm"] == "ours-dynamic")
    shutil.copyfile(our_result["solution"], AREA / "competitive_streaming_videos.out")
    if bench.judge(target, AREA/"competitive_streaming_videos.out", args.judge) != int(our_result["official_score"]):
        raise ValueError("Final instance judge mismatch")
    config.update(input_sha256=bench.digest(target), solution_sha256=bench.digest(AREA/"competitive_streaming_videos.out"))
    frozen.write_text(json.dumps(config, indent=2)+"\n", encoding="ascii")
    print("Selected",name,our_result["official_score"],"sha256",config["input_sha256"],flush=True)


def stress(args):
    config = json.loads((AREA/"competitive-instance-config.json").read_text())
    primary = AREA/"candidates"/config["primary"]
    definitions = configurations()
    # More computation for the controls, not just a weak greedy comparison.
    deep = dict(ls_passes=50, repack_passes=20, candidates=int(config["groups"])*24,
                exchange_rounds=50, cycles=3)
    for label, base in (("dynamic-deep","ours-dynamic"),("static-deep","static-advanced"),
                        ("zero-deep","zero-advanced"),("greedy-deep","greedy-advanced")):
        definitions[label] = {**definitions[base], **deep}
    rows = []
    # Three fresh JVM repetitions of the primary instance for honest timing summaries.
    for repetition in range(3):
        for label, settings in definitions.items():
            result = bench.experiment(primary, f"{label}-repeat{repetition+1}", settings,
                                      args.judge, args.timeout, AREA/"stress")
            rows.append(dict(instance=primary.name, algorithm=label, repetition=repetition+1,
                             official_score=result["official_score"], algorithm_ms=result["algorithm_ms"],
                             construction_ms=result["construction_ms"], search_ms=result["search_ms"],
                             wall_seconds=result["wall_seconds"], solution=result["solution"],
                             solution_sha256=result["solution_sha256"], status=result["status"]))
            bench.write_csv(AREA/"stress-results.csv",rows)
    # Strong reference portfolio: independently built/repaired starts, then exact best retention.
    summaries = []
    import statistics
    for label in definitions:
        subset = [r for r in rows if r["algorithm"] == label]
        if len({r["official_score"] for r in subset}) != 1:
            raise ValueError("Nondeterministic score in repeated experiment")
        summaries.append(dict(algorithm=label, official_score=subset[0]["official_score"],
                              median_algorithm_ms=round(statistics.median(r["algorithm_ms"] for r in subset),3),
                              median_wall_seconds=round(statistics.median(r["wall_seconds"] for r in subset),6),
                              solution=subset[0]["solution"]))
    portfolio = [r for r in summaries if r["algorithm"] in ("static-advanced","zero-advanced","greedy-advanced",
                  "static-reverse","zero-reverse","static-deep","zero-deep","greedy-deep")]
    winner = max(portfolio, key=lambda r:int(r["official_score"]))
    portfolio_names = {r["algorithm"] for r in portfolio}
    portfolio_times = [sum(r["algorithm_ms"] for r in rows if r["repetition"] == rep and r["algorithm"] in portfolio_names)
                       for rep in (1,2,3)]
    portfolio_walls = [sum(r["wall_seconds"] for r in rows if r["repetition"] == rep and r["algorithm"] in portfolio_names)
                       for rep in (1,2,3)]
    summaries.append(dict(algorithm="multistart-control-portfolio",official_score=winner["official_score"],
                          median_algorithm_ms=round(statistics.median(portfolio_times),3),
                          median_wall_seconds=round(statistics.median(portfolio_walls),6),
                          solution=winner["solution"]))
    bench.write_csv(AREA/"competitive-instance-results.csv",summaries)
    best_ours = max((r for r in summaries if r["algorithm"] in ("ours-dynamic","dynamic-deep")),
                    key=lambda r:int(r["official_score"]))
    shutil.copyfile(best_ours["solution"], AREA/"competitive_streaming_videos.out")
    config.update(stress_configurations=definitions, final_our_configuration=best_ours["algorithm"],
                  solution_sha256=bench.digest(AREA/"competitive_streaming_videos.out"),
                  stress_note="Added after mixed-family validation showed no consistent advantage; original selection preserved")
    (AREA/"competitive-instance-config.json").write_text(json.dumps(config,indent=2)+"\n",encoding="ascii")
    print("Stronger evaluation", json.dumps(summaries), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["screen","strengthen","validate","stress"])
    parser.add_argument("--judge", type=Path, default=Path("tmp/judgeHashCode2017.exe"))
    parser.add_argument("--groups", type=int, default=16)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    os.chdir(ROOT)
    AREA.mkdir(parents=True, exist_ok=True)
    {"screen":screen,"strengthen":strengthen,"validate":validate,"stress":stress}[args.action](args)


if __name__ == "__main__":
    main()
