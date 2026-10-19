# Seven fresh-run regressions

This diagnosis uses the frozen V1 final run, not its most favorable repeat.
`regression-v1-allocation.csv` records per-start allocation from the original
traces. Original files and outputs are unchanged. All three starts were built
on every regression. None was rejected as a duplicate; starvation happened
during repair, not construction selection.

| Input | Previous | V1 fresh | Observed limitation |
|---|---:|---:|---|
| 3860_246_84901_66 | 1977330 | 1976698 | One LS pass on dynamic/zero, about eight seconds in partial large-capacity DP, no exchanges or static repair |
| 6970_311_100000_69 | 1839095 | 1837677 | Dynamic/zero one-pass LS, most remaining time in DP, static receives only limited LS |
| 10000_500_100000_100 | 2020361 | 2019567 | Three seconds constructing, one dynamic/zero LS pass, remaining time in DP |
| custom_universallambda42_asymmetric | 976467 | 975704 | Five seconds constructing, interleaved partial repair, only part of dynamic repacking completed |
| instance2 | 1966777 | 1965930 | About 9.5 seconds in dynamic/zero DP after one LS pass; old guarded pipeline completes in under three algorithm seconds |
| kittens | 1025096 | 1023807 | Three constructors and partial first-pass LS consume most time; only part of dynamic DP finishes |
| videos_worth_spreading | 612117 | 611477 | All starts repaired, then stagnation before budget: different basin and bounded neighborhoods, not merely insufficient time |

## Mechanisms confirmed in code

The predecessor uses dynamic regret weight 1 in all seven cases. The first five
references are its frozen general pipeline. Worth/kittens references come from
longer dynamic-start packing/exchange/repack run graphs, not the cheaper shared
pipeline (which scores 611405/1025026). Their archived records cannot honestly
be described as ten-second references.

V1's early-repack dynamic/zero arms run one LS pass, versus ten in the shared
pipeline and up to fifty in the stronger historical run graph. V1 removed the
old deterministic DP-work guard. It therefore spends expensive partial DP on
large-capacity inputs whose predecessor skipped that phase. Static's ten-pass
arm is not protected against starvation. The reward term almost saturates at
0.2 for large raw gains, while exploration counts cache/pair units rather than
elapsed computation. A large DP unit and an empty pair scan are not comparable.

V1 applies the best swap *within each pair immediately*, unlike the predecessor's
best swap across the scanned pairs. Both are correct but follow different
trajectories. It scans at most 1000 seeded pairs and 64 popular videos/cache.
It clears every cached transfer row after each move although unchanged videos'
rows remain exact. Finally, random single-cache destruction often repairs the
same basin and can stop immediately on an empty selected cache. Historical
ablation did not establish a benefit, so V2 does not inherit that default.

## Targeted response

Protect a conservative dynamic prefix, retain a complementary packing-first
trajectory, use exact global-best exchanges, invalidate transfer rows only for
changed videos, and expand bounded repacking after initial exploration. For
large caches a complementary minimum-loss knapsack can represent removing enough
size from the candidate union rather than selecting up to the whole capacity.
This is the same exact bounded-pool objective, with an explicitly tested tie
policy, not a larger neighborhood. Skip repeated repacks only when the cache's
dependency version and candidate limit are unchanged.

Controlled replay outputs and timings are stored separately under
`reference-replays/` and `runs/controlled-v1-*`. Matched-budget outcome tables
are generated after those runs finish. No conclusion of universal dominance or
full local optimality follows from this diagnosis.

## Completed controlled replay (30-second caps)

| Input | Frozen shared pipeline, score / algorithm s | V1 adaptive | V1 portfolio | V1 intensive |
|---|---:|---:|---:|---:|
| 3860_246_84901_66 | 1977330 / 3.876 | 1977123 / 30.130 | 1977301 / 30.188 | 1977054 / 30.130 |
| videos_worth_spreading | 611405 / 4.731 | 611477 / 8.149 | 611443 / 8.444 | 611503 / 16.461 |
| asymmetric | 976467 / 11.361 | 976464 / 30.927 | 976464 / 30.919 | 976365 / 30.935 |

All scores above were recomputed by the original judge. Times are individual
algorithm measurements, excluding parsing/startup/judging; cooperative V1 caps
allow final-validation overhead. The shared pipeline completed below the cap.
Its shorter runtime does not imply that it dominates every longer search.
Worth's stronger 612117 reference is a separately replayed longer run graph,
not the shared pipeline above. Increasing V1's cap therefore fixes neither its
worth stagnation nor its large-capacity allocation problem by itself.
