# General-purpose solver: protocol and results

## Scope and safety

Work stays on `experiment/local-search-tuning`. Historical solver implementations,
reports, solutions, supplied judge and benchmark scores are preserved. No commits,
merges, branch changes or pushes are part of this study. The new CLI routes only
`ultimate`, `adaptive`, `portfolio` and `competition` to a separate engine; the old
two-argument Greedy shorthand and web worker behavior are unchanged.

The mandatory first phase completed 33/33 feasible, scored outputs before the
optimization experiments. `coverage-33.csv` and `coverage-solutions/` preserve that
snapshot. Final fresh results and archive-protected results are separate columns;
an archive record is not evidence that a fresh default solve reproduced it.

## Input support and exact scoring

Every original was checked independently for dimensions, complete line structure,
sizes, capacities, IDs, duplicate links, connection counts, latency ranges,
positive counts and trailing data. The five original nonconforming inputs violate
only the per-description request-count maximum, not the mathematical objective.

| Input | Original rows | Oversized rows | Largest count | Direct split rows | Aggregate-then-split rows |
|---|---:|---:|---:|---:|---:|
| custom_dejavu42.in | 998968 | 217903 | 12782 | 1216871 | 675577 |
| custom_universallambda42.in | 717907 | 145147 | 12000 | 863054 | 863054 |
| INSATANC2D.in | 399997 | 86844 | 12782 | 486841 | 270247 |
| INSTANCED.in | 37408 | 7601 | 12000 | 45009 | 45009 |
| medium_skew_dense_dejavu.in | 640030 | 20850 | 12000 | 660880 | 187353 |

Exact aggregation of identical `(video, endpoint)` demands followed by splitting
into chunks at most 10000 makes **all five derived inputs compliant**, including
the million-row case for which direct splitting alone fails. Demands and totals
are compared exactly before/after; every output has the same raw objective and
score on both versions. Derived files are reproducibly generated under ignored
`tmp/ultimate-normalized/`; hashes and audits are versioned.

Validation labels deliberately distinguish:

- `ORIGINAL_OFFICIAL`: conforming original, supplied unmodified C++ judge.
- `OFFICIAL_FORMAT_WIDE_JUDGE`: conforming original, but supplied judge's signed
  32-bit request total overflows (`medium_flat_dense_dejavu.in`).
- `EXTENDED_WIDE_JUDGE`: original per-description counts exceed official limits.
  Its score is mathematically valid, **not an official result on that original**.

The latter outputs are also checked on the compliant derived inputs. The derived
`INSTANCED` and `medium_skew_dense_dejavu` use the original judge safely; the other
three still require wider totals even after normalization. Both C++ judges agree
on every arithmetic-safe original. The original judge is never modified.

An independent Python evaluator uses arbitrary-precision integers, strict output
line counts, ID/duplicate checks and capacity checks. C++ and Java scores must
match it. `input-support-audit.csv` proves a signed-long bound for every actual
input, including the score numerator. The new Java parser accepts positive long
counts with checked aggregation/products and rejects objectives beyond its
explicit `Long.MAX_VALUE/4` supported bound rather than overflowing. The final
score numerator uses `BigInteger`; search differences stay exact signed longs.
This is support for the actual 33 plus an explicitly bounded extended domain,
not a claim of arbitrary unbounded input support.

`judge-exact.cpp` is an additional independent validator, not a replacement for
either historical judge. It reads signed-64-bit positive counts, rejects malformed
outputs, duplicates and capacity violations, and uses unsigned 128-bit totals,
products and score numerators. The checked input limits bound its numerator below
2^128. All final outputs are cross-checked with it as well. Extended count and
malformed-output regression tests exercise the wider-than-32-bit cases.

## One engine, three modes

This is a Java solver, not a script that runs every historical configuration.
`GeneralSolver` maintains an exact `PlacementState` per live trajectory, sharing
its memberships, used capacities, inverse endpoint lists and best/second
latencies across search operations. No dataset names or hashes select algorithms.

The small construction set is dynamic regret weight 1, canonical zero regret,
and indexed static regret weight 1. `BudgetedConstruction` uses the existing
exact ranking/tie policy and explicit feasibility invalidation, with cooperative
time checks. Differential tests compare it against the historical constructor.
Completed starts are deduplicated by exact placement bitsets. Hash matching is
used only by the experiment harness to admit and validate saved incumbents.
Original byte SHA-256 is always retained. Historical official copies differ only
in CRLF/LF endings, so archive admission additionally compares SHA-256 after
**only** CRLF-to-LF normalization, then rejudges the output on the actual input.
No numeric token is normalized for archive identity. This recovers the historical
612117 and 1025096 records; numeric input changes cannot match this check.

| Mode | Allocation | Repair | Diversification |
|---|---|---|---|
| Exhaustive Portfolio (`portfolio`) | Fixed round-robin over selected distinct starts | Historical-style ten LS passes, K64 repacking, bounded exchanges | None |
| Adaptive (`ultimate` / `adaptive`) | Initial trials, then measured progress with periodic exploration | Early repacking on dynamic/zero; conservative static trajectory retained | Complementary starts |
| Intensive (`competition`) | Same adaptive engine, default larger budget | K128, deeper repacking and exchange limits | Seeded partial cache destruction and exact repair |

"Exhaustive" means all selected portfolio strategies, not exhaustive enumeration
of the placement space. Under a deadline it is a **budget-truncated portfolio**,
not a guarantee that all historical trajectories finish. Historical unrestricted
records are a separate quality reference. No global optimality is claimed.

Construction reserves at most 60% of the nominal budget. Portfolio divides this
among starts; adaptive avoids starting another expensive index when the measured
construction cost would leave too little time. Remaining work is performed in
cache-level or cache-pair-level units. Adaptive utility combines current relative
quality, an exponentially smoothed exact gain/millisecond and an exploration
bonus; every eighth unit visits a least-tried live start. This is an interpretable
empirical allocation rule, not a learned predictor or a theoretical bandit bound.

Repair accepts exact positive insertions/replacements, bounded-pool knapsack
repacks and feasible cross-cache video exchanges. Transfer rows are cached only
while the placement is unchanged; accepted moves invalidate them. Same-video
interactions are excluded from swaps, so two distinct-video transfer deltas add
exactly. No stale move gains are reused. Incomplete knapsack work is discarded
without modifying its valid incumbent. Every accepted move refreshes all changed
videos. Equal rounded scores never mask a decrease in raw objective.

Intensive perturbation removes one eighth (at least one placement) from a seeded
cache in a **separate working copy**, then repairs it. The global best is retained
even if repair fails. Eight unsuccessful trials stop this exploration. Full
multi-cache joint optimization, path relinking, simulated annealing and tabu
search were not added; their value is not established by these experiments.

## Profile-guided changes and development

The first interrupted pilot is retained as exploratory data, not mixed into the
frozen comparison. On `dense2`, the old pipeline's construction is 33210, ordinary
LS 33218, then repacking 284493. Historical LS costs about 4.1 seconds there,
versus about 0.82 seconds repacking. A ten-pass prefix can consume a short budget
before the productive neighborhood. This motivated early repacking, **not a
change to the latency-saving objective**.

The profiled one-cache evaluator's repeated stored-video scan was replaced only
in the new engine by a suffix table: for each required size, find the exact
lowest-loss feasible removal, breaking ties by video ID. This reduces a
`videos * stored` scan to `stored + 1000 + videos` without pruning moves. Existing
algorithms are untouched. `operation-profiles.csv` contains batch timing estimates
(including scheduling/checkpoint overhead), not hardware sampling profiles.

Early repacking is not universally preferable: development found that retaining
the static ten-pass trajectory recovers the stronger mixed-case outcome. These
are staged development decisions, not held-out discoveries. The final source
hash/policy was frozen before the held-out evaluation and final suite runs.

## Experimental design

Development: tiny size-pressure case, `dense2`, mixed-demand medium input and
regional topology. Held-out from **this policy selection**: larger structured
input, a different dense case, sparse medium input and skewed-popularity input.
Some families and all promotion files were seen in older studies; this is not
a pristine unseen-author generalization test. Official four inputs are already
in the 33 and are not counted twice. V1/V2 are diagnostics, not tuning targets;
existing scarcity/packing/overlap/mixed seed-29 inputs provide additional checks.

The three modes run sequentially at equal nominal 1- and 3-second budgets,
three fresh-JVM repeats each. Standard final fresh budget is 10 seconds; incumbent
continuation uses a separate 10-second intensive run. Larger-budget checks use
the same 10/30-second caps for all modes on two representative costly official
inputs, followed by repeats of promising configurations. Compilation and judge
time are excluded. Parsing, construction and search are separately recorded;
worker wall time is also retained.

Deadlines are cooperative, not hard-real-time process kills. Timed search stops
at safe boundaries, and final validation/copying may overrun the nominal cap.
Use the **observed runtime columns**, especially for short budgets. Small timing
differences are not claimed significant. Fixed seeds control shuffles; wall-clock
cutoffs and measured gain-rate scheduling can still change scores across runs.
Ranges/medians are reported, archived outputs have exact hashes, and fixed-work
portfolio tests are deterministic. A command replay is not a promise of bitwise
identity for time-adaptive runs on another machine.
The frozen policy records both byte-exact and CRLF/LF-canonical source hashes;
portable checkout line endings are accepted, but algorithmic source edits are not.

## Results

Final measurements are exported in `all-33-results.csv`, `comparison-medians.csv`,
`larger-budget-comparison.csv`, `ablation.csv` and `summary.json`. Best outputs are
in `best-solutions/`, with input/output hashes in the 33-row table. Independent
rejudging is recorded in `independent-validation.csv`.

### Final 33/33 outcome

Fresh default: **13 improvements / 13 ties / 7 regressions** against the fixed
predecessors. Mean relative change +0.165678%, median 0%, worst -0.125744% (kittens).
Archive: **19 improvements / 14 ties / 0 regressions**, mean +0.188559%, median
+0.001366%. Total point gain is 37050 across this mixed-status suite: 8218 on
original-judge inputs, 121 on the conforming wide-judge input, and 28711 on
extended originals. It is **not an official competition total**. The five extended
predecessors are the initial coverage constructors, not nonexistent earlier
official records; their larger gains must be interpreted in that context.

The frozen fresh suite used 166.455 algorithm seconds in total, including final
validation overhead. Initial incumbent continuations used 194.389 seconds;
larger-budget comparisons, repeats and final polishing are additional offline
work, not hidden inside the fresh 10-second policy. Individual metadata records
separate construction, search, parsing and worker wall time.

Final incumbent polishing was identical on the two costly official cases:
competition, 30-second cap, one dynamic constructor alongside the saved incumbent.
Worth stayed at 612126 (8.959 s, stagnation); kittens improved 1025096 -> 1025110
(31.436 s, time cap). Compared with historical records, worth gains nine points
(+0.001470%) and kittens fourteen (+0.001366%). All six new global-best events
on worth and all twelve on kittens were triggered by **K128 one-cache repacking**,
adding 4396454 and 14241130 raw request-ms respectively. These record gains are
not attributed to perturbation or to a newly invented cross-cache neighborhood.
Kittens' last best improvement was about 15.096 seconds into that continuation;
the remaining time did not improve it. Search stops after this declared batch.

Below, runtime is the **fresh default's** observed algorithm time, not the cost
of producing an offline archive record. O = valid conforming original, supplied
official judge; W = valid conforming original, overflow-safe judges; E = valid
extended original, **not official on that original**. Full machine-readable
status/configuration/provenance and input/output hashes are in the CSV.

| Instance | Previous | Fresh | Final best | Gain | Fresh s | Validation |
|---|---:|---:|---:|---:|---:|---|
| 2_2_2_1.in | 951327 | 951327 | 951327 | 0 | 0.018 | O |
| 4_2_4_2.in | 982271 | 982271 | 982271 | 0 | 0.023 | O |
| 6_2_6_2.in | 998499 | 998499 | 998499 | 0 | 0.028 | O |
| 8_2_8_2.in | 991480 | 991480 | 991480 | 0 | 0.022 | O |
| 20_2_20_2.in | 995902 | 995902 | 995902 | 0 | 0.027 | O |
| 3860_246_84901_66.in | 1977330 | 1976698 | 1977357 | 27 | 10.132 | O |
| 4990_246_84901_50.in | 1800315 | 1800875 | 1800875 | 560 | 10.062 | O |
| 6970_311_100000_69.in | 1839095 | 1837677 | 1839154 | 59 | 10.056 | O |
| 10000_500_100000_100.in | 2020361 | 2019567 | 2020397 | 36 | 10.495 | O |
| big.in | 271267 | 271267 | 271267 | 0 | 1.062 | O |
| custom_dejavu42.in | 63985 | 64684 | 64684 | 699 | 3.532 | E |
| custom_universallambda42.in | 1340105 | 1341357 | 1342172 | 2067 | 10.301 | E |
| custom_universallambda42_asymmetric.in | 976467 | 975704 | 976467 | 0 | 11.036 | O |
| dense2.in | 284493 | 284493 | 284493 | 0 | 4.014 | O |
| dense.in | 69683 | 69683 | 69683 | 0 | 3.344 | O |
| INSATANC2D.in | 130990 | 132328 | 132328 | 1338 | 4.616 | E |
| instance1.in | 1497840 | 1498391 | 1498453 | 613 | 10.020 | O |
| instance2.in | 1966777 | 1965930 | 1966778 | 1 | 10.025 | O |
| INSTANCED.in | 1272926 | 1296139 | 1296458 | 23532 | 2.439 | E |
| kittens.in | 1025096 | 1023807 | 1025110 | 14 | 11.445 | O |
| me_at_the_zoo.in | 512115 | 512115 | 512115 | 0 | 0.039 | O |
| medium_flat_dense_dejavu.in | 89890 | 90010 | 90011 | 121 | 5.549 | W |
| medium_flat_sparse_lambda.in | 23843 | 23866 | 23880 | 37 | 4.382 | O |
| medium_mixed_lambda.in | 51621 | 51722 | 51722 | 101 | 2.250 | O |
| medium_skew_dense_dejavu.in | 146022 | 147097 | 147097 | 1075 | 2.691 | E |
| medium_skew_sparse_lambda.in | 43671 | 43689 | 43710 | 39 | 1.433 | O |
| realistic_large_clustered.in | 943306 | 949461 | 949473 | 6167 | 10.258 | O |
| realistic_large_random.in | 929876 | 929996 | 930431 | 555 | 10.339 | O |
| regional_instance.in | 2383868 | 2383868 | 2383868 | 0 | 0.520 | O |
| supra.in | 411743 | 411743 | 411743 | 0 | 0.249 | O |
| trending_today.in | 499966 | 499966 | 499966 | 0 | 7.148 | O |
| videos_worth_spreading.in | 612117 | 611477 | 612126 | 9 | 8.287 | O |
| zipF_instance.in | 1928623 | 1928623 | 1928623 | 0 | 0.613 | O |

### Matched 3-second caps: median score / observed algorithm seconds

| Split / input | Portfolio | Adaptive | Intensive |
|---|---:|---:|---:|
| development / 8_2_8_2.in | 991480 / 0.022 s | 991480 / 0.026 s | 991480 / 0.030 s |
| development / dense2.in | 284493 / 3.044 s | 284493 / 3.047 s | 284493 / 3.050 s |
| development / medium_mixed_lambda.in | 51722 / 2.103 s | 51722 / 2.273 s | 51698 / 2.868 s |
| development / regional_instance.in | 2383868 / 0.616 s | 2383868 / 0.625 s | 2383868 / 0.632 s |
| heldout / 3860_246_84901_66.in | 1976810 / 3.137 s | 1976580 / 3.134 s | 1976580 / 3.135 s |
| heldout / dense.in | 69683 / 2.858 s | 69683 / 2.824 s | 69683 / 3.048 s |
| heldout / medium_flat_sparse_lambda.in | 23862 / 2.514 s | 23866 / 2.450 s | 23868 / 3.088 s |
| heldout / zipF_instance.in | 1928623 / 0.720 s | 1928623 / 0.659 s | 1928623 / 0.875 s |

Adaptive ties portfolio on all development scores at three seconds, so the
simpler adaptive policy is frozen as the default rather than intensive mode.
At one second the dense2 medians are 33229 / 284493 / 147115 respectively;
the wide score ranges in the raw data matter more than tiny time differences.
Held-out evidence is mixed: the portfolio wins one three-second case by 230,
adaptive wins sparse by four, and two tie. At one second adaptive beats portfolio
on three held-out cases and ties one. This supports budget robustness, **not
universal score dominance**. Held-out results did not trigger further tuning.

### Compact ablations: official scores at a common 3-second cap

| Configuration | dense2 | medium mixed | regional |
|---|---:|---:|---:|
| Adaptive, all starts | 284493 | 51722 | 2383868 |
| Dynamic only | 284493 | 51692 | 2383868 |
| Static only | 284493 | 51722 | 2373219 |
| Zero only | 284493 | 51519 | 2372844 |
| No repacking | 33229 | 51483 | 2376139 |
| No exchanges | 284493 | 51568 | 2383856 |
| Intensive | 284493 | 51698 | 2383868 |
| Intensive without perturbations | 284493 | 51698 | 2383868 |

Repacking is decisive on dense2. Exchange/repair adds 154 on mixed and 12 on
regional in these trajectories. Dynamic and static are complementary: no single
start wins every tested input. Perturbations do not improve these three scores,
so they are **not enabled in the default**. The fixed/adaptive schedule comparison
above is the allocation ablation; unimplemented multi-cache joint optimization
and path relinking are N/A, not credited with a gain.

V1/V2 diagnostics reproduce 980693 and 1014358 with each mode. Additional existing
mixed/overlap/packing/scarcity seed-29 checks also tie across modes. Those are
consistency checks, not new held-out-family superiority results.

### Larger budgets, fresh starts

These are single observations per cell, not medians. Each cell is score /
observed algorithm seconds; every score comes from an independently judged output.

| Input / cap | Portfolio | Adaptive | Intensive |
|---|---:|---:|---:|
| worth / 10 s | 611443 / 9.106 | 611477 / 8.591 | 611503 / 10.035 |
| worth / 30 s | 611443 / 8.619 | 611477 / 8.806 | 611503 / 16.520 |
| kittens / 10 s | 1022744 / 11.481 | 1024573 / 11.422 | 1024780 / 11.417 |
| kittens / 30 s | 1024304 / 31.485 | 1024793 / 31.411 | 1024812 / 31.405 |

Intensive is useful as an optional quality/cost trade-off, but none of these fresh
trajectories beats the stronger historical records. Increasing a cap does not
force a stagnated run to use it. Worth's 10-second intensive configuration was
repeated twice: all three scores are 611503, median 10.033 seconds. Kittens'
30-second intensive configuration gives 1024812/1024812/1024813, median score
1024812 and median time 31.419 seconds. The one-point spread is timing-sensitive
search progress, not statistically meaningful superiority.

The standard 10-second fresh kittens observation in the 33-row table is 1023807;
the separate larger-budget study's fresh 10-second adaptive observation is
1024573. Both are retained. Gain-rate scheduling and short time cutoffs can select
different trajectories despite fixed shuffle seeds. Do not replace the former
with the more favorable repeat when describing the frozen final-suite run.

### Post-freeze new-seed checks

Two new inputs were generated only after freezing the policy, using the existing
unclustered generic generator, not the challenge-instance generator. Seeds 773177
and 902117 and the two feature combinations were fixed before their scores were
seen. Three repetitions per mode at a 3-second cap give:

| New input | Portfolio | Adaptive | Intensive |
|---|---:|---:|---:|
| sparse-flat | 366101 / 0.755 s | 366101 / 0.782 s | 366101 / 1.398 s |
| dense-skew | 1775673 / 0.794 s | 1775673 / 0.666 s | 1775600 / 1.341 s |

Each cell is median score / median algorithm time. These are unseen seeds from a
known generator, **not unseen-author or unseen-family evidence**. The 73-point
intensive regression on dense-skew is retained. Larger candidate pools can alter
the trajectory unfavorably even though every accepted move on that trajectory
is positive. This further supports keeping intensive mode optional.

### Validation and preserved controls

The clean build, legacy regression suite, 640 historical construction traces,
160 exchange searches, 240 packing searches, 80 repacks and 20 exhaustive
knapsack comparisons pass. New checks add 150 differential construction cases,
150 move-by-move verified searches, fixed-work deterministic replay, incumbent
non-regression, checked wide aggregation and overflow rejection. Seven Python
tests cover strict validity, exact normalization, arbitrary-precision scoring,
wide C++ counts, malformed outputs and CRLF/LF identity; the seven earlier
promotion-tool tests also pass.

The unmodified Greedy was rerun on the four original inputs and judged at
507906 / 608302 / 499794 / 1021681, exactly its historical references. The live
`ultimate` zoo demo is independently judged at 512115. Historical benchmarks
are preserved rather than relabelled as matched-budget observations. In
particular the older shared kittens pipeline's 1025026 took 40.264 algorithm
seconds; the new fresh 10-second cap is cheaper but lower quality. These two
numbers do **not** establish a same-quality speedup.

## Reproduction

From the repository root, Java 17+, Python 3.9+ and g++ are sufficient; no extra
runtime libraries are needed:

```powershell
.\test.ps1
python .\test-ultimate-tools.py
.\run-ultimate-solver.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Archive "<promotion-zip>"
java -Xmx2g -cp out com.hashcode.streaming.Main ultimate examples\me_at_the_zoo.in tmp\demo.txt --seconds 10
.\tmp\judgeHashCode2017.exe examples\me_at_the_zoo.in tmp\demo.txt
java -Xmx2g -cp out com.hashcode.streaming.Main competition examples\kittens.in tmp\intensive.txt --seconds 30
python .\ultimate-experiments.py intensive
python .\ultimate-experiments.py repeat
python .\ultimate-experiments.py unseen
python .\ultimate-experiments.py polish
python .\ultimate-experiments.py run
python .\ultimate-experiments.py validate
python .\ultimate-experiments.py summary
```

Resume checks input/output hashes and rejudges saved results. Delete no archived
results to "improve" a summary. Run metadata includes every exact CLI command,
configuration, source hash, seed, raw objective, judge hash and stop reason.
Time traces retain actual checkpoints; frontier rows are never invented for
budgets a run did not reach; short runs retain their actual finish point.
`--starts dynamic|static|zero` and `--no-repack`,
`--no-exchange`, `--no-diversify` reproduce the compact ablations. A long budget
plus `--max-units N` supports fixed-work portfolio experiments.

## Files changed in this phase

New solver sources: `GeneralMain`, `CheckedInputParser`, `BudgetedConstruction`
and `GeneralSolver`. `Main.java` adds only routing to the new modes; historical
algorithm sources are unchanged. `GeneralSolverTest` and a test-runner entry add
differential checks. `ultimate-experiments.py`, `run-ultimate-solver.ps1`,
`test-ultimate-tools.py` and this folder contain the new experiment workflow,
strict judge, raw data, report and solution archives. README adds the CLI/options.
Previously dirty generator, ignore rules and older studies are preserved.

The documented root build/validation wrapper and live demo were executed.
`git diff --check` passes. Commands use portable relative paths. Compiled classes, judge executables,
logs, extracted/normalized inputs and development previews remain ignored. Work
stays uncommitted on the original experimental branch; no push or merge occurs.

## Limitations

The candidate neighborhoods and cache-pair coverage are bounded; stagnation is
**not a certificate of a full local optimum**. Early cancellation may produce
only a partial constructor, particularly under very short caps. Dense gains use
`8 * videos * caches` bytes during construction. Repacking is pseudopolynomial
in capacity and skips decisions exceeding its explicit 64-MiB bitset work guard.
Transfer-row storage is bounded and invalidated after changes. Only actual suite
success, not universal memory feasibility or global optimality, is established.

Larger K, stronger construction score and more runtime do not guarantee that a
different trajectory dominates another one. Incumbent retention prevents archive
regression, but cannot make a weak fresh solver look like an improvement. The
report and exports retain fresh regressions and negative ablations explicitly.
