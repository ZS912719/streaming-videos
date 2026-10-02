#!/usr/bin/env python3
"""Generate reproducible Hash Code 2017 experiments using only the standard library."""

import argparse
import bisect
import csv
import hashlib
import json
import random
from dataclasses import asdict, dataclass, replace
from pathlib import Path


@dataclass(frozen=True)
class Scenario:
    name: str
    factor: str
    videos: int = 2000
    endpoints: int = 100
    caches: int = 60
    descriptions: int = 20000
    degree: int = 8
    alpha: float = 1.0
    capacity: int = 5000


def scenarios(include_stress):
    base = Scenario("reference", "reference")
    result = [base]
    result += [replace(base, name=f"capacity_{capacity}", factor="capacity",
                       capacity=capacity) for capacity in (1250, 2500, 10000)]
    result += [replace(base, name=f"zipf_{alpha:.1f}", factor="popularity",
                       alpha=alpha) for alpha in (0.6, 1.4)]
    result += [replace(base, name=f"degree_{degree}", factor="connectivity",
                       degree=degree) for degree in (2, 24)]
    result += [replace(base, name="scale_small", factor="scale", videos=500,
                       endpoints=30, caches=20, descriptions=3000, degree=4),
               replace(base, name="scale_large", factor="scale", videos=5000,
                       endpoints=300, caches=150, descriptions=80000, degree=12)]
    if include_stress:
        result.append(replace(base, name="stress", factor="stress", videos=10000,
                              endpoints=500, caches=250, descriptions=150000, degree=24))
    return result


def domain_rng(seed, domain):
    # Domain-specific generators preserve pairing when only one factor changes.
    return random.Random(f"streaming-videos-v1:{seed}:{domain}")


def generate(path, scenario, seed):
    s = scenario
    assert 1 <= s.videos <= 10000 and 1 <= s.endpoints <= 1000
    assert 1 <= s.caches <= 1000 and 1 <= s.descriptions <= 1000000
    assert 0 <= s.degree <= s.caches and 1 <= s.capacity <= 500000
    sizes_rng = domain_rng(seed, "sizes")
    topology_rng = domain_rng(seed, "topology")
    demand_rng = domain_rng(seed, "requests")
    ranking_rng = domain_rng(seed, "ranks")
    sizes = [sizes_rng.randint(10, 1000) for _ in range(s.videos)]
    ranks = list(range(s.videos))
    ranking_rng.shuffle(ranks)
    cumulative = []
    total_weight = 0.0
    for rank in range(1, s.videos + 1):
        total_weight += rank ** (-s.alpha)
        cumulative.append(total_weight)

    degrees = []
    unique_demands = set()
    total_requests = 0
    contribution_count = 0
    with path.open("w", encoding="ascii", newline="\n") as output:
        output.write(f"{s.videos} {s.endpoints} {s.descriptions} {s.caches} {s.capacity}\n")
        output.write(" ".join(map(str, sizes)) + "\n")
        for endpoint in range(s.endpoints):
            dc_latency = topology_rng.randint(600, 4000)
            # About 5% of endpoints are disconnected, including endpoint zero.
            degree = 0 if endpoint % 20 == 0 else s.degree
            degrees.append(degree)
            output.write(f"{dc_latency} {degree}\n")
            # Generate a full permutation so density variants share prefix links.
            cache_order = topology_rng.sample(range(s.caches), s.caches)
            latency_order = [topology_rng.randint(1, 500) for _ in range(s.caches)]
            for cache, latency in zip(cache_order[:degree], latency_order[:degree]):
                output.write(f"{cache} {latency}\n")

        for _ in range(s.descriptions):
            endpoint = demand_rng.randrange(s.endpoints)
            rank = bisect.bisect_left(cumulative, demand_rng.random() * total_weight)
            video = ranks[rank]
            count = demand_rng.randint(1, 10000)
            output.write(f"{video} {endpoint} {count}\n")
            total_requests += count
            key = (endpoint, video)
            if key not in unique_demands:
                unique_demands.add(key)
                contribution_count += degrees[endpoint]

    # The index uses six bytes per contribution. Pair arrays use at least 20 bytes
    # per possible pair during construction; JVM objects and transient arrays add more.
    index_array_bytes = 6 * contribution_count
    pair_array_bytes = 20 * s.caches * s.videos
    return {
        **asdict(s), "seed": seed, "file": path.name,
        "unique_demands": len(unique_demands),
        "total_requests": total_requests,
        "contribution_count": contribution_count,
        "contribution_upper_bound": s.descriptions * s.degree,
        "index_array_mb": round(index_array_bytes / 1048576, 3),
        "pair_array_mb": round(pair_array_bytes / 1048576, 3),
        "file_bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parent / "tmp" / "experiments")
    parser.add_argument("--seeds", type=int, nargs="+", default=[17, 29, 43])
    parser.add_argument("--include-stress", action="store_true")
    parser.add_argument("--only", nargs="+", help="Generate only named scenarios")
    args = parser.parse_args()
    suite = scenarios(args.include_stress)
    if args.only:
        unknown = set(args.only) - {s.name for s in suite}
        if unknown:
            parser.error("Unknown scenarios: " + ", ".join(sorted(unknown)))
        suite = [s for s in suite if s.name in args.only]
    if len(set(args.seeds)) != len(args.seeds):
        parser.error("Seeds must be unique")

    # Keep each run separate; never overwrite an existing experiment directory.
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        parser.error("Output directory is not empty; choose a new output directory")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for seed in args.seeds:
        for scenario in suite:
            path = args.output_dir / f"{scenario.name}.seed_{seed}.in"
            row = generate(path, scenario, seed)
            rows.append(row)
            print(f"Generated {path.name}: {row['unique_demands']} unique demands, "
                  f"{row['contribution_count']} contributions")

    with (args.output_dir / "manifest.csv").open("w", encoding="ascii", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    configuration = {"generator_version": 1, "seeds": args.seeds,
                     "scenarios": [asdict(s) for s in suite],
                     "recommended_java_heap": "4g", "recommended_parallel_solvers": 1,
                     "memory_note": "Array estimates exclude JVM objects and transient arrays.",
                     "workload_note": "Synthetic aggregate requests; no temporal order model."}
    (args.output_dir / "configuration.json").write_text(
        json.dumps(configuration, indent=2) + "\n", encoding="ascii")
    print(f"Generated {len(rows)} datasets in {args.output_dir}")


if __name__ == "__main__":
    main()
