# Promotion benchmark

## Scope and integrity

The study started from a clean `experiment/local-search-tuning` branch at
`ec801dab8220335a9b685225effd510a9f17b607`. No branch change, commit, push or
merge was performed. Existing algorithms, historical reports, measurements and
record outputs were retained. The new Java class only composes existing solvers.

The current user's Downloads contained `instances_promo.zip`, not an extracted
promotion directory. It contains 33 flat `.in` files, 33 distinct SHA-256 hashes,
no nested directories and no unsupported files. Its SHA-256 is:

```text
a19c03a5324ad08591d880cdfb58ff57a85f491970ed6a4ddf15e53e4f1e8f0a
```

`instance-inventory.csv` preserves original relative paths, hashes, byte counts,
dimensions, demands and validation outcomes. All entries are checked before
extraction. Traversal, absolute paths, Windows aliases/alternate streams, case
collisions, symlinks and excessive sizes are rejected. Extraction is under
ignored `tmp/promo-inputs/`; the original ZIP is unchanged. The default ordering
is natural, case-insensitive relative filename order. No Sheet order was supplied.

Validation follows the supplied Google 2017 task PDF: V <= 10000, E/C <= 1000,
R <= 1000000, capacity <= 500000, video sizes 1..1000, datacenter latency
2..4000, cache latency 1..500 and below DC, valid unique endpoint links and
request counts 1..10000. Exact line arities, indices and trailing data are checked.
LF and CRLF are accepted as equivalent portable whitespace. Duplicate request
descriptions are allowed and correctly aggregated by the existing Java parser.

**28 inputs are valid; five are invalid. No input was silently repaired.**

| Invalid input | Maximum request count | Out-of-range descriptions |
| --- | ---: | ---: |
| custom_dejavu42.in | 12782 | 217903 |
| custom_universallambda42.in | 12000 | 145147 |
| INSATANC2D.in | 12782 | 86844 |
| INSTANCED.in | 12000 | 7601 |
| medium_skew_dense_dejavu.in | 12000 | 20850 |

These files have blank scores and explicit invalid status in the named export.
They must be corrected by their suppliers or accepted under separately stated
non-official rules before inclusion. No zero was invented for them.

## Supplied judge arithmetic

The valid `medium_flat_dense_dejavu.in` contains 2560718818 total requests,
which overflows the supplied judge's `int totalRequest`. With our valid output,
the original compiled judge prints **-132728**, while the exact Java objective
and the separately authorized wide-integer judge both give **89890**.

`judgeHashCode2017-wide.cpp` is a separate copy with exactly two arithmetic
changes (ignoring trailing whitespace): `long long totalRequest` and `1LL`
promotion of the per-request score product. Parsing, constraints, placement
evaluation and division are otherwise unchanged. The original source was not
edited. Its SHA-256 remains:

```text
d6bc06c33d67cecfa17c01a24882d890b0b6481a36b2e4847c91ee9936aa1153
```

The main table uses the **original judge for 27 inputs**, and the explicitly
labelled wide-integer copy for this one input. Both binaries were also run on
all 28 final outputs: they agree on every safe input. The full reconciliation
is in `judge-width-comparison.csv`. The 89890 value must not be represented as
the unmodified judge's output. The promotion organizer may need to use the same
arithmetic correction for this valid input.

## Configuration selection before final evaluation

Previous official-instance research narrowed the pilot to four configurations:
dynamic regret, static regret, zero regret with identical repair, and dynamic
regret with limited exchanges. A five-input pilot was declared in code before
scores were measured: `8_2_8_2.in`, `6970_311_100000_69.in`, `dense2.in`,
`medium_mixed_lambda.in`, `regional_instance.in`. Features, not scores, motivated
the coverage of tiny, large-capacity, dense, mixed and sparse regional structures.

| Configuration | Mean relative deficit to pilot best | Total algorithm seconds |
| --- | ---: | ---: |
| dynamic-repack-exchange | 0% | 12.279 |
| dynamic-repack | 0.015084% | 10.325 |
| static-repack | 0.134879% | 10.769 |
| zero-repack | 8.736837% | 10.569 |

`pilot-results.csv` contains all 20 results, with archived outputs and per-stage
judge checks. The chosen configuration is quality-first, with modest added
pilot runtime. Timing differences of a few milliseconds are not significant.
`configuration.json` was frozen before the main benchmark. The later judge
arithmetic correction did not change solver settings.

### Selected promotion benchmark configuration

1. Existing exact dynamic OCAG constructor, feasible-alternative regret, weight 1.
2. One-cache insertion/replacement LS: best improvement, forward cache/candidate
   order, one accepted move/cache/pass, at most 10 passes, stop on a no-move pass.
3. Cache repacking: incumbents plus top 64 absent benefit/size candidates, exact
   0/1 knapsack, up to five passes, stop on a no-improvement pass.
4. Indexed exchanges: fixed seed 20170317 for cache-pair ordering, first 1000
   pairs, top 64 stored videos/cache by popularity then ID, best improvement,
   at most five rounds, stop when the scanned set has no improving exchange.
5. Repeat the same one-cache LS. One cycle only; no packing-pair stage.

Two deterministic work guards are part of the frozen policy, not retrospective
per-instance tuning. Repacking is skipped if
`sum_c (incumbents(c)+64) * capacity * 5 > 3000000000` estimated DP cells.
Exchange indexing is skipped if `storedCopies * caches > 50000000` transfer slots.
Nine inputs triggered the repacking guard, including the large-capacity files,
the two realistic large inputs and trending_today. No exchange guard triggered.
Skipped stages are explicit rows in `stage-results.csv` and run metadata.

Each worker has a 4 GB Java heap and a 180-second external safety limit. A timeout
retains the last completed validated checkpoint and is labelled partial, not
completed. **No main run failed or timed out.** These are fixed work limits, not
identical elapsed time allocations. No dataset name enters algorithm behavior.

## Main results and spreadsheet exports

All 28 valid inputs completed: 140 stage outputs were internally validated,
fully rescored and checked by the applicable C++ judge. No duplicate placements,
capacity violations, nonmonotone objectives or internal/judge inconsistencies
remained. Final outputs and their hashes are archived under `solutions/` and
`runs/`. Main scores do not mix retrospectively chosen algorithms.

`promo-scores.csv` and `promo-scores.tsv` contain all 33 filenames and statuses.
`promo-scores-only.txt` contains **28 numbers**, in inventory order with invalid
rows omitted. `scores-only-order.csv` is the exact line-to-instance mapping.
Do not paste these 28 numbers against an unfiltered 33-row list.

| Instance | Score | Algorithm seconds |
| --- | ---: | ---: |
| 2_2_2_1.in | 951327 | 0.023 |
| 4_2_4_2.in | 982271 | 0.023 |
| 6_2_6_2.in | 998499 | 0.022 |
| 8_2_8_2.in | 991480 | 0.025 |
| 20_2_20_2.in | 995902 | 0.026 |
| 3860_246_84901_66.in | 1977330 | 4.977 |
| 4990_246_84901_50.in | 1800315 | 3.976 |
| 6970_311_100000_69.in | 1839095 | 7.431 |
| 10000_500_100000_100.in | 2020361 | 11.634 |
| big.in | 271267 | 1.395 |
| custom_universallambda42_asymmetric.in | 976467 | 14.730 |
| dense2.in | 284493 | 5.457 |
| dense.in | 69683 | 3.627 |
| instance1.in | 1497840 | 3.458 |
| instance2.in | 1966777 | 2.199 |
| kittens.in | 1025026 | 40.264 |
| me_at_the_zoo.in | 512115 | 0.031 |
| medium_flat_dense_dejavu.in (wide judge) | 89890 | 2.848 |
| medium_flat_sparse_lambda.in | 23843 | 1.927 |
| medium_mixed_lambda.in | 51621 | 1.142 |
| medium_skew_sparse_lambda.in | 43671 | 1.007 |
| realistic_large_clustered.in | 943306 | 19.481 |
| realistic_large_random.in | 929876 | 27.250 |
| regional_instance.in | 2383868 | 0.193 |
| supra.in | 411743 | 0.230 |
| trending_today.in | 499966 | 3.715 |
| videos_worth_spreading.in | 611405 | 7.086 |
| zipF_instance.in | 1928623 | 0.446 |

Total algorithm time was 164.623 seconds; total Java worker wall time was
224.884 seconds. Algorithm time excludes parsing, process startup, checkpoint
validation/writing, compilation and external judge execution. Worker wall time
includes startup, parsing and checkpoint work, but not the external judges.
Construction and search times are separate in `promo-details.csv`. Times are
individual measurements, not medians. Fresh replays may differ due to JIT,
OS caching and host load; no small runtime differences are claimed significant.
Environment: Java 21.0.10, release-17 classes, MinGW g++ 15.2.0, Python 3.13.2,
Windows 10, 12 logical processors. Runs were sequential, not parallel solver races.

Pilot cases are configuration-selection data, not held-out evidence. Four familiar
official datasets also occur in the archive and were used in earlier research.
`instance-features.csv` flags both roles. The remaining final-evaluation rows
were not used to retune the main configuration.

## Diagnostics and interpretation

In kittens, construction scores 1022060; initial LS adds 729 points, repacking
adds 1964, exchanges add 3 immediately and final LS adds another 270. Thus the
small immediate exchange gain can understate the subsequent repair it unlocks.
On the asymmetric input, repacking adds 1702 points after LS adds only 2.
On videos_worth_spreading, construction 607245 becomes 609523 after the capped
initial LS, 611378 after repacking, 611396 after exchanges and 611405 after LS.

Eighteen one-cache stages reached the pass cap, three repacking stages reached
their cap and fifteen exchange stages reached the round cap. A stopped capped
stage is not a local-optimum certificate. Completed no-move LS passes establish
only an optimum in the stated insertion/one-for-one neighborhood. Repacking and
exchange certificates are restricted to their candidate pools/scanned pairs.

The shared pipeline deliberately does not replace historical per-instance records:
512115, 612117, 499966, 1025096. Their outputs/hashes remain unchanged. Main worth
and kittens scores are lower because this benchmark freezes one economical
policy rather than reusing each dataset's individually optimized trajectory.

## Reproduction

From the repository root:

```powershell
# Locate, safely extract, build, resume, judge and export.
.\run-promo-benchmark.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
# Alternate archive location.
.\run-promo-benchmark.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Archive "<archive-path>"
# Recompute all 28 runs in fresh JVMs; compare scores AND exact output hashes.
.\run-promo-benchmark.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase replay
# Recreate the pilot without changing the frozen protocol.
.\run-promo-benchmark.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase pilot
python .\test-promotion-tools.py
.\test.ps1
```

Run JSON contains exact parameters, stage scores, raw objectives, stop reasons,
timings, output hashes and judge binary hashes. Replay writes only under ignored
`tmp/promotion-replay/`, preserving original measurements. Resume rehashes and
rejudges archived solutions before reuse. Archive/hash mismatches must be resolved
explicitly; they are not missing scores to replace with zero.

An independent fresh-JVM replay recomputed all 28 main outputs and matched every
official score and exact output SHA-256. Its new timings are preserved separately
in `fresh-replay-results.csv`, not substituted for the original benchmark timings.
The documented PowerShell build/resume/reconciliation workflow was also executed
successfully from the repository root.
