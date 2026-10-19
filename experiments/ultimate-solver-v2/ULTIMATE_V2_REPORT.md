# Ultimate solver V2

## Initial state and safeguards

This study stays on `experiment/local-search-tuning` at the existing base
revision `ec801dab8220335a9b685225effd510a9f17b607`. No branch switch, commit,
push, reset or merge is part of the work. Historical solvers, datasets, reports,
outputs and the supplied C++ judge are preserved. V2 has a separate engine and
CLI route. The reconstructed canonical V1 source fingerprint is still exactly
`35eff7a5673eb1e401275e4acdad7a559e3a96a87ca73eb0a6b86096880f6500`.

V1's frozen ten-second fresh suite improved 13 inputs, tied 13 and regressed
on seven versus its predecessors. Its archive improved 19 and tied 14. The
archive includes previous computation and is not a fresh-solve measurement.
The five extended predecessors were construction-only coverage outputs, so
their larger gains are not evidence against equally optimized predecessors.

`REGRESSION_DIAGNOSIS.md` and `regression-v1-allocation.csv` investigate all seven
regressions. All five shared-pipeline references were freshly reproduced, as
were the stronger 612117 worth and 1025096 kittens record run graphs. Every
replayed stage matched its exact raw objective and original C++ judge score.
The saved V1 archive, including 612126/1025110, remains the record baseline.

## Hypotheses and staged procedure

1. Protect a complete conservative dynamic repair prefix instead of starving
   it through cache-level racing. Retain a complementary packing trajectory.
2. Restore global-best exchanges over the stated pair set. V1's immediate
   pair-best acceptance is valid but follows a different trajectory.
3. Avoid repeating unchanged benefit calculations, transfer calculations and
   complete unproductive scans. Preserve exact objective differences and ties.
4. Explore bounded K32/64/128/256 or K512 repacks after the initial pipeline,
   and allow another useful cycle while time remains. Larger K is not assumed
   to dominate a different trajectory.
5. Test fixed, adaptive and intensive scheduling at matched caps. Protect initial
   trials before exploiting quality and measured progress. Test pattern transfer
   separately rather than inheriting unsuccessful random destruction.

The ledgers retain successive versions and negative results. Development uses
large-capacity structure, dense/mixed inputs and the two expensive official
cases. There is no uncontrolled parameter Cartesian product. The final source
and policy are frozen before the new held-out topology tests and final suite.

## Architecture and exactness

This is one Java engine with shared placement state per live trajectory, not a
shell script replaying every historical configuration. The constructors are
dynamic regret weight 1, canonical zero regret and indexed static regret weight
1. Exact placement-bitset deduplication removes redundant constructors. A copy
of the dynamic start is intentionally retained for a different packing-first
repair path, not claimed as a fourth distinct constructor.

The primary prefix is LS10, guarded K64 repack up to five passes, five global-best
exchanges over 1000 seeded pairs/top64 videos, then LS10. The complementary path
uses LS50, packing10, up to twenty global-best exchanges over its initial pair
set, then LS50. Later cycles alternate exchange, ordinary repair and deeper
repacking. The packing trajectory also revisits packing. A positive cycle can
continue until the deadline; there is no arbitrary final cycle-count cutoff.

Fixed policy visits live trajectories round-robin. Adaptive first protects the
primary pipeline, gives initial exploration to complementary starts, then uses
relative objective, normalized improvement rate and elapsed-work exploration.
Intensive additionally protects the packing trajectory and expands to K512
and deeper exchange limits. All policies preserve the global best. Incumbent
mode first protects deeper repair of its admitted saved placement. No strategy
uses an input filename or input hash to select algorithmic behavior.

The final allocation correction bounds initial complementary trials by actual
repair time: a target of 5 seconds or 10% of the budget, whichever is smaller.
Intensive's packing trial targets 12 seconds or 25%. These are checked between
atomic search units, not hard preemption limits. After its primary
prefix, the dynamic trajectory may complete its first deep pass within a
20-second/50% repair allowance. Subsequent exploration targets 20% of measured
repair work, not 20% of arbitrarily sized cache/pair units. Progress rates are
updated at complete cache passes or global exchange rounds. Incumbent mode
protects its first seven deeper cycles before racing complementary starts.

This addresses the completed 60-second pilots: kittens remained at 1025026
after reaching that score around 17 seconds, while weaker trajectories used
most remaining repair time. Profiling identified packing and repacking work,
not scheduler overhead. No constructor or move evaluator was changed by this
allocation correction.

### Final policy selection before freezing

The bounded-time allocation was tested at the same 30-second cap:

| Input | Fixed | Adaptive | Intensive |
|---|---:|---:|---:|
| Kittens | 1024741 | 1025050 | 1025050 |
| Worth | 611870 | 611970 | 612119 |
| 3860 large-capacity | 1978318* | 1978303 | 1978295 |

The fixed large-capacity observation (*) precedes the allocation-only change;
fixed scheduling and its move evaluators did not change. These single timed
observations are policy-selection evidence, not statistically significant
rankings. Intensive improves Worth by 149 over adaptive, ties Kittens, and
loses 8 on the large-capacity input. Earlier screening also covers mixed and
dense structures. Intensive is selected as the shared final policy, with
pattern transfer disabled. The default CLI policy changes to intensive only;
the evaluated search kernel remains the bounded-trial version.

The final full-source fingerprint is
`605dfbb16766b3a88c71a9a8509c3b5d23446132b0f581b35ca122f9eea764d2`.
`solver-configuration.json` and `source-versions/` preserve that freeze before
final evaluation. No result from the final suite is used to retune it.

Since each trajectory is monotone, the best placement can be retained by
reference instead of copying every cache after every tiny improvement. Timed
snapshots are separate copies. If an atomic move straddles a checkpoint, its
edits are undone only in that snapshot, preserving the preceding feasible best.
The caller's supplied incumbent is copied before any search modification.

### Benefit and transfer indexes

`BenefitIndex` lazily initializes a dense cache-excluded benefit row when needed.
After modifying a video, it refreshes that video's column in every initialized
row. Other columns are independent because the objective separates by video.
This replaces repeated full request/cache scans without changing any benefit,
candidate pool or ranking. Kittens has 69651168 sparse contributions per full
benefit pass. Dense storage costs `8 * videos * caches` bytes per trajectory:
40 MB on kittens, approximately 160 MB for four trajectories. These are array
estimates, not measured total process peaks.

Exact transfer rows are versioned by video. Changing a video invalidates all
its stored-copy rows, while other videos' rows remain valid. Cache dependency
versions also include direct membership/capacity changes. A no-move result is
reused only when every dependency and the scanned coverage remain unchanged.
No stale positive gain is reused.

### Exchange pruning and ties

For a video v removed from A, an exchanged video w from B must satisfy
`size(v)-free(B) <= size(w) <= size(v)+free(A)`. A size-ordered index restricts
evaluation to this feasible interval before duplicate checks and exact transfer
lookups. The winning comparator explicitly preserves seeded pair order, then
the original popularity/video-ID ranks in A and B. Size ordering therefore
does not silently change equal-gain selection.

### Repacking and diversification

Repacking retains all incumbents plus top-K absent benefit/size candidates and
solves their exact knapsack. For large capacities the complementary formulation
minimizes lost value when removing size at least `sumSizes-capacity`. It need
consider removal size only up to `deficit+largestItem-1`: a minimum-loss feasible
removal can be made inclusion-minimal, whose overshoot is below its largest
item. All benefits are nonnegative. This bounds a different DP representation
of the same candidate-pool optimum. Exact optimum values are tested, while ties
can select different valid subsets from the primal formulation.

Incomplete DP work never changes a placement. The reconstruction bitset guard
is 64 MiB per repack. Initial deterministic work guards preserve the inexpensive
primary trajectory. Later search uses the smaller feasible DP representation,
dependency-aware skipping and the remaining time budget.

Uniform random destruction was not inherited: earlier failures showed repairs
returning to the same basin. Optional pattern transfer instead considers a
complete cache configuration from another current trajectory. Its exact gain
is the difference of cache-excluded benefits, and capacity follows from the
valid donor configuration. It accepts only strictly positive gain. In optimized
30-second pilots it ties mixed at 51757 and lowers worth from 612024 to 612004.
Those are timed single observations, not significance evidence. They do not
justify enabling it by default. No joint two-cache DP, annealing, tabu method,
genetic algorithm or new regret formula was added.

## Controlled performance measurements

Measured environment: Intel Core i7-10750H at 2.60 GHz, 12 logical processors,
Windows 10 build 19045, Java 21.0.10 compiled for Java 17, Python 3.13.2,
g++ 15.2.0 (MinGW-w64),
and one solver process at a time with `-Xmx4g`. The CPU model was read from
the local processor registry entry. This is the actual measurement host,
not the Ryzen target mentioned in the older dataset-generator documentation.
The GPU is not used. Array-size estimates are not measured whole-process peaks.

All measurements below use three fresh-JVM runs and identical fixed work, not
equal round counts with different candidate coverage. Both compared variants
produce identical complete output SHA-256 hashes and exact raw objectives.

| Control | Before median algorithm s | After median algorithm s | Observation |
|---|---:|---:|---|
| Kittens, 3000 units, snapshot/no-move retention | 11.274 | 10.837 | Overlapping ranges; no significant speedup claimed |
| Kittens, 6000 units, cached benefits | 16.285 | 9.437 | Search component about 9.60 to 2.79 seconds |
| Worth, 60000 units, size pruning | 9.515 | 7.872 | Same selected swaps and placement |

The first is an exploratory combined engineering change, not an isolated causal
estimate of copying cost, and is not conflated with the benefit-index result. Raw
measurements are in `snapshot-copy-comparison/results.csv` and
`index-performance.csv`. Compilation, external parsing checks and judge runtime
are excluded from algorithm time. OS/JIT variation remains visible in raw rows.

## Validation protocol and interpretation

Every reported final output must pass capacity, identifier, duplicate and format
checks, independent arbitrary-precision Python evaluation, the applicable C++
judge, the wide judge and the strict 128-bit validator. Internal Java scores
must agree. Record replacement compares exact raw objectives, not just floored
scores, and verifies input/output hashes before admission.

Validation modes remain distinct: O is a conforming original evaluated with the
unchanged supplied judge; W is a conforming original requiring its overflow-safe
equivalent; E is an extended original, not an officially conforming original.
All five E inputs are also exactly aggregated and split into compliant derived
inputs, with identical demands, totals, raw objectives and scores. Request counts
are never clamped or aggregated into 32-bit integers.

Tests cover the historical regression suites, 640 constructor traces, 160
exchange searches, 240 packing searches, 80 historical repacks and 20 exhaustive
knapsack comparisons. V1's 150 constructor controls and 150 verified searches
remain. V2 adds 300 verified searches/replays, 100 exact index/pruning placement
controls, 3000 arbitrary benefit-index mutations and 160 exhaustive one-cache
checks, plus incumbent and timed-checkpoint preservation. Python tests cover
source admission, archive monotonicity, input identity and existing exact judges.

Fresh evaluation never loads an archived placement. The separate incumbent
pass starts from the strongest admitted historical/fresh/pilot placement and
cannot erase an earlier record. Its additional runtime is not credited to the
fresh solver. All development computation is separate offline cost.

JVM uptime budgeting includes parsing and much of startup. Worker wall time
also includes native launch overhead. Final validation/output can exceed the
cooperative search deadline and are reported separately. This is not a hard
real-time 180-second guarantee. Actual checkpoints cannot credit a move that
finished after their requested time. Early-finished runs may publish their
final feasible placement without waiting. A long-policy checkpoint is not
silently relabelled as an independently configured short run.

Matched-budget controls use the same nominal cap and report actual worker wall
time. V1 starts its timer after parsing; V2 subtracts JVM uptime, so their caps
are not identical hard process deadlines. Both total algorithm measurements
include final engine validation. V2 separates validation from search; V1's
legacy search residual includes its final validation. Worker-wall comparisons
avoid confusing those differently defined search fields. Solution-writing time
is separate in V2; worker wall also includes trace/metadata export and startup.

Stagnation concerns only the explicit candidate pools and scanned pair sets.
There is no proof of global optimality, no assertion that every possible
multi-cache reassignment has been checked, and no universal generalization
claim. Presentation and final result tables follow the completed ledgers.

## Completed fresh benchmark: 33/33

One shared frozen intensive policy, seed 20170317, 180-second cooperative cap,
fresh JVM and no saved placement on every input. All 33 outputs have independent
Python/C++ agreement. Final 128-bit archive revalidation is reported separately.

| Reference | Improved | Tied | Lower | Mean relative gain | Median relative gain |
|---|---:|---:|---:|---:|---:|
| Pre-V1 references | 22 | 11 | 0 | 0.239906% | 0.054792% |
| V1 frozen fresh, 10-second cap | 22 | 11 | 0 | 0.074032% | 0.083556% |
| V1 best-known archive | 21 | 11 | 1 | 0.051148% | 0.014095% |

These are unweighted per-input relative changes, not an official competition
aggregate. The ten-second comparison is a longer-budget quality comparison,
not equal-budget evidence. Archive scores incorporate historical computation.
The fresh pass used 1728.713 algorithm seconds and 1740.163 worker-wall seconds,
excluding external judging. Timed suite results are single observations, not
median runtime estimates. Repeated fixed-work controls are reported above.

### Seven original regressions: actual fresh-run checkpoints

| Input | Pre-V1 reference | 10 s | 30 s | 60 s | 180 s | First reaches reference, JVM s |
|---|---:|---:|---:|---:|---:|---:|
| 3860_246_84901_66 | 1977330 | 1978063 | 1978263 | 1978375 | 1978409 | 2.168 |
| 6970_311_100000_69 | 1839095 | 1839949 | 1840073 | 1840100 | 1840156 | 1.721 |
| 10000_500_100000_100 | 2020361 | 2021176 | 2021355 | 2021366 | 2021468 | 3.068 |
| asymmetric | 976467 | 976467 | 976569 | 976569 | 976569 | 5.716 |
| instance2 | 1966777 | 1968047 | 1968057 | 1968057 | 1968057 | 0.613 |
| kittens | 1025096 | 1024738 | 1025050 | 1025084 | 1025188 | 65.217 |
| worth | 612117 | 611666 | 612119 | 612119 | 612119 | 22.509 |

All seven pre-V1 regressions are eliminated by 180 seconds. These are actual
checkpoints of the 180-second configuration, independently judged, not separate
short-budget policy runs. Early-finished cases publish their final placement
without waiting. Target-crossing times come from exact-objective traces plus
the recorded engine offset, on the JVM-uptime clock, excluding native launch.
Source, output hashes and observed checkpoint times are retained.

Worth remains seven points below the stronger V1 archive record of 612126.
Its fresh trajectory stops in bounded-neighborhood stagnation at 52.176
algorithm seconds. More time alone does not repair that basin. The archive
therefore retains the stronger old placement pending incumbent improvement.

Kittens reaches a new fresh record of 1025188, 78 above the V1 archive and 92
above the replayed pre-V1 reference. Construction takes 4.631 seconds, search
175.108 seconds and final validation 1.413 seconds. Worker wall is 181.735
seconds. Its dynamic trajectory finishes at 1025188 after 37.880 seconds of
its own repair work; the packing trajectory reaches 1025144 and static reaches
1025086. A leader that has exhausted its configured search is not starved by
continuing exploration of still-live trajectories. This is not a proof that
all unrestricted moves have been exhausted.

The final kittens raw objective first appears in a `local` move on the dynamic
trajectory at 82.252 seconds of JVM uptime, after its deeper repack/exchange
cycles. This is trace-derived time to the final raw value, not a separately
judged 82-second checkpoint. Continued exploration makes no further global gain.

The historical stronger fixed run graphs were also fully replayed: Worth
612117 requires 46.753 algorithm / 59.087 worker-wall seconds across 37 stages;
Kittens 1025096 requires 184.239 algorithm / 279.311 worker-wall seconds across
23 stages. The full Kittens replay is not falsely labelled a 180-second control.

## Completed incumbent benchmark and validation

The separate continuation pass completed 33/33 inputs. It admits the strongest
verified previous/fresh/pilot output, not a filename-specific configuration.
Compared with the V1 archive, the resulting archive improves 21 inputs, ties
12 and regresses on none: +0.051903% mean relative improvement, +0.014095%
median, and 12834 summed diagnostic points. That point sum is not an official
competition aggregate. The archive includes previous/offline computation.

Continuation improves five fresh placements' integer scores: 3860 gains 2,
6970 gains 13, 10000 gains 20, clustered gains 189 and realistic-random gains
19. Worth keeps the older 612126 rather than fresh V2's 612119. Other cases
gain no additional integer points. Stronger raw objectives at the same integer
score are still retained. Continuing every input costs 1774.327 algorithm /
1785.979 worker-wall seconds, so it is not uniformly cost-effective.

Both required passes plus final independent validation completed. Total worker
wall time is 3526.142 seconds (58.77 minutes). The first worker-log timestamp to
the validation manifest spans 3845.043 seconds (64.08 minutes), including input
admission, hashing, external judging, normalization and archive I/O, but not
initial compilation or later comparison experiments. `benchmark-timing.json`
records this timing definition and the UTC endpoints.

`independent-validation.csv` contains 66 PASS rows. Each output agrees with
independent Python, the applicable supplied/wide C++ judge, the wide judge and
the strict 128-bit validator. The five extended originals each have a compliant
normalized-derived counterpart with identical demand and objective; these have
explicit separate status, judge and score columns. There are 27 O, 1 W and
5 E inputs per pass. All 2439 historical artifacts in the frozen integrity
manifest and the supplied judge source retain their original hashes.

All Java regression/differential suites and the 7+7+4 Python checks pass again
after the benchmark. Legacy Greedy is additionally checked on the four original
datasets, independently of V2. No legacy algorithm source is changed.

## Full matched-budget V1 control

The unchanged V1 default (`ultimate`, alias adaptive) was also run fresh on all
33 inputs at a 180-second nominal cap. These are new control runs, not the old
ten-second results and not incumbent-assisted scores. V2 wins 22, ties 11 and
loses none. Mean relative gain is 0.050588%, median 0.014095%, with 9673 summed
diagnostic points. This is descriptive single-run evidence, not a significance
test or a proof of dominance on unseen workloads.

| Metric against the same pre-V1 references | V1 10 s | V1 180 s | V2 180 s |
|---|---:|---:|---:|
| Improved | 13 | 18 | 22 |
| Tied | 13 | 13 | 11 |
| Regressed | 7 | 2 | 0 |
| Mean relative gain | 0.165678% | 0.189120% | 0.239906% |
| Median relative gain | 0% | 0.015558% | 0.054792% |
| Total algorithm seconds | 166.455 | 1330.359 | 1728.713 |

V1's matched control uses 1342.643 worker-wall seconds versus V2's 1740.163.
All three promotion passes (33 fresh V2, 33 continuation, 33 matched V1)
consume 4868.785 worker seconds, or 81.15 minutes. From the first worker to
the final comparison revalidation manifest, elapsed workflow time is 5418.346
seconds (90.31 minutes). This includes held-out/competitive controls and
external checking, but not subsequent report/CSV review. Exact scope and UTC
endpoints are recorded in `benchmark-timing.json`.

Equal caps do not imply equal consumed time: several V1 trajectories stop
earlier. V2 consumes about 29.6% more aggregate worker time for higher scores.
Quality is the primary objective, but this cost must not be hidden. Kittens
illustrates the trade-off: V1 scores 1025132 in 88.979 worker seconds; V2 scores
1025188 after the full run, with its final raw value first recorded at 82.252
JVM seconds. Trace-derived time to best and full execution time are distinct.

Increasing V1's budget alone resolves five of the seven original regressions.
Asymmetric remains at 976465 versus reference 976467; Worth remains at 611477
versus reference 612117. V2 resolves both and improves the other five further.
It would be incorrect to attribute every recovered regression solely to V2's
redesign. Conversely, the remaining equal-cap score differences cannot be
explained merely by comparing 180 seconds against ten.

### Frozen general-purpose fixed pipeline

All seven controlled fixed-pipeline replays complete within the same 180-second
allowance. They consume less time than the full V2 runs but score lower on all
seven. This is the frozen shared promotion pipeline, not a claim that every
historical individually tuned run graph is weaker at every runtime.

| Input | Fixed score | Fixed worker s | Fresh V2 score |
|---|---:|---:|---:|
| 3860_246_84901_66 | 1977330 | 5.510 | 1978409 |
| 6970_311_100000_69 | 1839095 | 5.556 | 1840156 |
| 10000_500_100000_100 | 2020361 | 9.452 | 2021468 |
| asymmetric | 976467 | 21.050 | 976569 |
| instance2 | 1966777 | 1.936 | 1968057 |
| kittens | 1025026 | 48.588 | 1025188 |
| worth | 611405 | 5.103 | 612119 |

The stronger manual Worth graph reaches 612117 and the historical archive
still holds 612126, above fresh V2 by seven. Those distinctions remain visible.

## Frozen held-out topology tests

Two new topology families were declared before evaluation, with fixed seeds
173801 (ring overlap) and 772039 (hierarchical hubs). The policy was frozen
before generating/evaluating these inputs. Both use 30-second caps, no supplied
placements, and original-judge-compliant inputs. All six modes finish early.

| New family | V1 adaptive | V1 portfolio | V1 intensive | V2 fixed | V2 adaptive | V2 intensive |
|---|---:|---:|---:|---:|---:|---:|
| Ring overlap | 1339594 | 1339594 | 1339594 | 1339594 | 1339594 | 1339594 |
| Hierarchical hubs | 1923237 | 1923237 | 1923237 | 1923237 | 1923237 | 1923237 |

Every score is independently judged. V2 has no regression here, but also no
measured quality advantage. These are two small synthetic families, not evidence
of universal superiority or realistic traffic generalization. Their ties do
not justify retuning the already frozen policy.

### Existing synthetic and competitive diagnostics

These are diagnostics on previously available inputs, not held-out claims.
Both solvers receive ten-second caps and all finish in under one worker second.
The old temporary generic seed-29 inputs were absent, so the saved mixed and
overlap seed-29 candidates are used without regenerating or changing them.

| Existing input | Fresh V1 | Fresh V2 | Gain |
|---|---:|---:|---:|
| competitive_streaming_videos.in | 980693 | 982507 | 1814 |
| best-v2.in | 1014358 | 1015489 | 1131 |
| mixed.seed_29.in | 971994 | 971994 | 0 |
| overlap.seed_29.in | 947830 | 947830 | 0 |

All scores have independent C++ agreement. Tiny runtime differences are not
treated as statistically meaningful. `diagnostic-results.csv` preserves the
input/output hashes, commands and measured times.

## Final complete promotion scores

Previous means the frozen V1 best-known archive, not V1's short fresh run.
O = conforming original, supplied judge. W = conforming original requiring
overflow-safe equivalent arithmetic. E = extended original; its compliant
normalized-derived counterpart has the same exact score, but does not make
the original conforming. All five E originals are included.

Record source credits the final integer score: F = achieved fresh, I = achieved
through incumbent continuation, P = previously achieved and retained. A later
placement can improve exact raw value without creating a new integer record;
full file provenance remains in the CSV. Percentages are relative to Previous.

| Input | Judge | Previous | Fresh V2 | Final archive | Gain | Relative gain | Record source |
|---|---|---:|---:|---:|---:|---:|---|
| 2_2_2_1.in | O | 951327 | 951327 | 951327 | 0 | 0% | P |
| 4_2_4_2.in | O | 982271 | 982271 | 982271 | 0 | 0% | P |
| 6_2_6_2.in | O | 998499 | 998499 | 998499 | 0 | 0% | P |
| 8_2_8_2.in | O | 991480 | 991480 | 991480 | 0 | 0% | P |
| 20_2_20_2.in | O | 995902 | 995902 | 995902 | 0 | 0% | P |
| 3860_246_84901_66.in | O | 1977357 | 1978409 | 1978411 | 1054 | 0.053303% | I |
| 4990_246_84901_50.in | O | 1800875 | 1801453 | 1801453 | 578 | 0.032096% | F |
| 6970_311_100000_69.in | O | 1839154 | 1840156 | 1840169 | 1015 | 0.055188% | I |
| 10000_500_100000_100.in | O | 2020397 | 2021468 | 2021488 | 1091 | 0.053999% | I |
| big.in | O | 271267 | 271267 | 271267 | 0 | 0% | P |
| custom_dejavu42.in | E | 64684 | 64783 | 64783 | 99 | 0.153052% | F |
| custom_universallambda42.in | E | 1342172 | 1342213 | 1342213 | 41 | 0.003055% | F |
| custom_universallambda42_asymmetric.in | O | 976467 | 976569 | 976569 | 102 | 0.010446% | F |
| dense2.in | O | 284493 | 284493 | 284493 | 0 | 0% | P |
| dense.in | O | 69683 | 69683 | 69683 | 0 | 0% | P |
| INSATANC2D.in | E | 132328 | 132551 | 132551 | 223 | 0.168521% | F |
| instance1.in | O | 1498453 | 1499643 | 1499643 | 1190 | 0.079415% | F |
| instance2.in | O | 1966778 | 1968057 | 1968057 | 1279 | 0.065030% | F |
| INSTANCED.in | E | 1296458 | 1296624 | 1296624 | 166 | 0.012804% | F |
| kittens.in | O | 1025110 | 1025188 | 1025188 | 78 | 0.007609% | F |
| me_at_the_zoo.in | O | 512115 | 512115 | 512115 | 0 | 0% | P |
| medium_flat_dense_dejavu.in | W | 90011 | 90248 | 90248 | 237 | 0.263301% | F |
| medium_flat_sparse_lambda.in | O | 23880 | 23889 | 23889 | 9 | 0.037688% | F |
| medium_mixed_lambda.in | O | 51722 | 51757 | 51757 | 35 | 0.067669% | F |
| medium_skew_dense_dejavu.in | E | 147097 | 147412 | 147412 | 315 | 0.214144% | F |
| medium_skew_sparse_lambda.in | O | 43710 | 43744 | 43744 | 34 | 0.077785% | F |
| realistic_large_clustered.in | O | 949473 | 950399 | 950588 | 1115 | 0.117434% | I |
| realistic_large_random.in | O | 930431 | 930901 | 930920 | 489 | 0.052556% | I |
| regional_instance.in | O | 2383868 | 2384204 | 2384204 | 336 | 0.014095% | F |
| supra.in | O | 411743 | 411743 | 411743 | 0 | 0% | P |
| trending_today.in | O | 499966 | 499966 | 499966 | 0 | 0% | P |
| videos_worth_spreading.in | O | 612126 | 612119 | 612126 | 0 | 0% | P |
| zipF_instance.in | O | 1928623 | 1931971 | 1931971 | 3348 | 0.173595% | F |

The 21 final integer records comprise 16 reached fresh and five whose final
value is improved through continuation. No matched V1 control beats fresh V2
on integer score, so those controls add no higher integer archive record.

## Conclusions and remaining limitations

Intensive remains the selected shared policy. It is a coherent Java engine,
not a shell portfolio of historical runs. A small constructor set, exact
placement deduplication, protected primary repair, bounded complementary
trials, measured-time exploration and progressive repacking work together.
No filename-specific algorithm rules are introduced.

Cached benefits and feasible-size exchange indexing have isolated fixed-work
speed evidence with identical complete placements. Global-best exchange
selection, deeper repacking and revised allocation contribute to the combined
quality result, but their individual score effects have not been isolated on
all 33 inputs. More time also helps V1. The report does not attribute the whole
gain to one mechanism or claim that adaptive scheduling always beats fixed.

The 180-second setting is useful on the expensive cases and kittens, whereas
many small inputs finish in seconds. Worth demonstrates a persistent basin
limitation: fresh V2 remains seven below the stronger old archive despite
stopping before its cap. Continuing all inputs adds comparatively few integer
points for another 29.77 worker minutes. That is justified for record hunting,
not automatically for every everyday solve.

No joint two-cache DP, new regret formula or additional metaheuristic is needed
to obtain these results. Whole-cache pattern transfer remains optional and off
because its controlled pilots do not justify default inclusion. Larger pools
and different basin transitions remain possible future work, not implemented
after the freeze.

Limitations:

- Candidate pools and pair/video limits remain bounded. Stagnation is not a
  global-optimality certificate; some live trajectories remain budget-limited.
- The final full-suite measurements use one fresh JVM per configuration. The
  repeated fixed-work controls report medians; full-suite timing differences
  are not claimed statistically significant. Timed stopping can vary results.
- The two held-out families tie all modes and are small synthetic workloads.
  Promotion tuning is not proof of generalization to unseen real traffic.
- Time caps are cooperative. Final validation, launch and output overhead are
  visible, not hidden as if the process obeyed a strict hard deadline.
- Final and last-allocation source overlays are preserved. Earlier exploratory
  prototypes retain version-labelled measurements, not a complete source-overlay
  archive for every intermediate version. The winning source/configuration and
  all final outputs are preserved.
- Archive byte hashes require preserved line endings when moving the study.
  Canonical source matching does not bypass input or solution identity checks.

## Delivery and reproduction

The frozen winning command uses the same settings on every input:

```powershell
.\build.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
.\test.ps1
java -Xmx4g -cp out com.hashcode.streaming.Main ultimate-v2 input.in output.txt --seconds 180 --policy intensive --seed 20170317
.\run-ultimate-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Archive "<promotion-zip>"
python .\ultimate-v2-experiments.py compare_v1
python .\ultimate-v2-experiments.py heldout
python .\ultimate-v2-experiments.py diagnostics
```

Repeat the benchmark command to resume validated cases without rerunning their
solvers. See `reproduction-instructions.md` for individual phases, incumbent
loading, independent validation, score export and frozen-source replay.

The current `ultimate-v2` default is intensive with the standard 60-second
budget; `--preset competition` selects 180 seconds. The two-argument Greedy
shorthand and all historical modes remain unchanged. The root demo was run:
V2 Zoo scores 512115, and original Greedy shorthand scores 507906. All four
original Greedy scores remain 507906 / 608302 / 499794 / 1021681, independently
confirmed in `legacy-greedy-regression.csv`.

CSV checks confirm 33 fresh rows, 33 archive rows, inventory order, matching
input identities, all archived output hashes, monotone raw/integer objectives
and the exact scores-only column. The compact table was also visually checked.
Build products, judge binaries, temporary diagnostics and preview files remain
ignored. The repository content scan finds no unwanted references or personal
hardcoded paths. No commit, push, merge, reset or branch switch was performed.
Both mandatory final passes and independent validation are complete.
