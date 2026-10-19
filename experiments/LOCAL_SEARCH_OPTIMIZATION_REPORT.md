# Local Search optimization study

## Initial state and audit

This study started from the committed OCAG and Local Search implementation on
`experiment/opportunity-cost-greedy`. Work took place on
`experiment/local-search-tuning`. The working tree was clean before branch
creation. Neither the original Greedy nor OCAG construction was changed.

Every starting placement and previous best was reproduced with the official
`judgeHashCode2017.cpp` before tuning:

Measurements used the same Windows host as the preceding scientific study
(Oracle Java 21.0.10, compiled with `--release 17`, MinGW g++ 15.2.0,
12 logical processors, and `-Xmx4g`). Solver timing excludes compilation and
judge execution. Local Search timing also excludes parsing and construction.
Three fresh JVM runs measure each tuned winner. The expensive extension is
reported as observed stage times from a bounded run and a separate complete
score reproduction, not as a runtime median. The exchange pair order has the
fixed seed 20170317.

| Dataset | Greedy | OCAG | Greedy + original LS | OCAG + original LS | Previous best |
|---|---:|---:|---:|---:|---:|
| me_at_the_zoo | 507906 | 507906 | 507906 | 507906 | 507906 |
| videos_worth_spreading | 608302 | 608329 | 609221 | 609323 | 609323 |
| trending_today | 499794 | 499794 | 499851 | 499851 | 499851 |
| kittens | 1021681 | 1021938 | 1021735 | 1021997 | 1021997 |

`LocalSearchSolver.solve` constructs Greedy, while `improve` mutates a supplied
placement. The original search traverses caches by increasing ID, calculates
exact insertion benefits and removal losses with the current cache excluded,
selects the best positive insertion or one-for-one replacement for each cache,
and accepts at most one move per cache per pass. It stops after a pass without
a move or three passes. It never accepts a nonpositive raw request-millisecond
delta. Stored videos are considered by removal loss then video ID; candidate
videos were considered by increasing video ID. There is no randomness.

The main adjustable restrictions are the pass cap, moves per cache, first or
best improvement, cache order, and candidate order. The best/second-best
latency index is a correctness-preserving implementation optimization. It is
refreshed after each accepted move. A video with no positive benefit is safely
skipped; removing an existing video when an insertion already fits cannot
increase the objective. Capacity is checked before a replacement. The winning
runs' final raw objectives match a fresh full scan, and every tested score
matches the judge.

## Tuning hypotheses and procedure

1. The three-pass cap may truncate useful improvements on `videos_worth_spreading`
   and `kittens`, whose original runs stopped at the cap.
2. More than one freshly reevaluated move per cache may change the search path.
3. First improvement and traversal order may escape different local optima.
4. Small improvements may continue after the rounded official score appears flat,
   so stopping must use exact raw gain.

Each run loaded the same saved Greedy or OCAG starting output. The search
runner records construction separately, checks capacity and duplicates, verifies
its incremental raw objective against a full recomputation, and writes a trace.
Every one of the 100 tuning outputs was independently judged. We first tested
pass counts on medium inputs, then move limits and order, and ran the promising
settings on `kittens` last. Candidate reversal was dropped after lower medium
scores. The complete configuration-level record is
[`local-search-tuning-results.csv`](local-search-tuning-results.csv).

Pass-count evidence from the unchanged best-improvement, forward-order search:

| Start/dataset | 1 pass | 3 passes | 5 passes | 10 passes | Converged score / passes |
|---|---:|---:|---:|---:|---:|
| OCAG / videos_worth_spreading | 608817 | 609323 | 609430 | 609504 | 609508 / 15 |
| OCAG / kittens | 1021984 | 1021997 | 1021999 | 1022000 | 1022000 / 8 |
| Greedy / trending_today | 499851 | 499851 | 499851 | 499851 | 499851 / 2 |
| Greedy / me_at_the_zoo | 507906 | 507906 | 507906 | 507906 | 507906 / 1 |

Thus three passes were a useful original runtime bound, but they were restrictive
on two instances. At convergence, an entire pass checks the stated insertion
and one-for-one replacement neighborhood and finds no positive raw move.

Move selection matters more than a large per-cache move limit on the medium
instance. With OCAG on `videos_worth_spreading`, 50-pass first improvement with
one move per cache reaches 609635, versus 609508 for best improvement. Two
first-improvement moves reach 609531; allowing five moves scores lower. From
Greedy, two first-improvement moves reach 609593, above one move's 609576.
On `trending_today`, two first-improvement moves raise the score to 499857.
On `kittens`, best improvement in reverse cache order reaches 1022004; first
improvement and larger move caps score lower. Reversing candidate video order
was tested on `videos_worth_spreading` and `trending_today` and did not improve
their records. It was not expanded to `kittens` after those results.

## Tuned original Local Search

The following configurations are individually selected from the controlled
search. `m` is the maximum freshly reevaluated moves per cache per pass. All
stop after a complete pass without a positive move; the safety pass cap was
not reached. Search times are medians of three fresh JVM runs from a saved
starting placement, with observed ranges in parentheses. Construction medians
come from the previous repeated scientific benchmark and are shown separately.

| Dataset | Start | Config | Official score | Executed passes / moves | Construction median | LS median (range) |
|---|---|---|---:|---:|---:|---:|
| me_at_the_zoo | Greedy | 3 passes, m1, best, forward | 507906 | 1 / 0 | 14.597 ms | 10.316 ms (8.491–11.243) |
| videos_worth_spreading | OCAG | 50 passes, m1, first, forward | 609635 | 49 / 676 | 317.221 ms | 1738.310 ms (1694.885–1928.569) |
| trending_today | Greedy | 50 passes, m2, first, forward | 499857 | 2 / 23 | 2554.949 ms | 368.128 ms (340.051–406.944) |
| kittens | OCAG | 30 passes, m1, best, reverse | 1022004 | 8 / 486 | 31110.066 ms | 6618.628 ms (6558.372–6790.939) |

The best tuned Greedy + LS scores are 507906, 609593, 499857 and 1021738.
The best tuned OCAG + LS scores are 507906, 609635, 499857 and 1022004.
OCAG remains the better start on the two instances where its construction
differs. The best single shared parameter setting among tested OCAG runs is
50 passes, m1, first improvement, forward order: its four scores are 507906,
609635, 499849 and 1021985. The individually tuned settings gain 27 total
score points over that shared setting. Summing scores across these four known
instances is only a benchmark-specific selection rule, not evidence for unseen
instances. The CLI's original three-pass default remains unchanged.

The archived [winning traces](local-search-winning-traces/) and
[`local-search-optimization-convergence.csv`](local-search-optimization-convergence.csv)
show per-pass progress. For the medium OCAG winner, score-equivalent gain
continues through pass 48, then pass 49 finds no move. `kittens` gains through
pass 7, then pass 8 finds none. `trending_today` gains in pass 1, then stops in
pass 2. These statements concern the stated one-cache neighborhood only.

## Optional two-cache exchange

The exact synthetic `opportunity-cost.in` case confirms that one-cache moves
can be stuck while exchanging videos between two caches raises the score from
549500 to the known optimum 925000. A deterministic probe of the tuned official
placements used seed 20170317 to order cache pairs. A broader full-pair probe
found a feasible exchange worth five official points on
`videos_worth_spreading`; smaller probes found no rounded-score gain elsewhere.

The optional exchange examines two *stored* videos in different caches. It
swaps them only if both post-swap capacities fit and neither destination would
contain a duplicate. The exact gain is recomputed for all requests for the two
affected videos. Every accepted exchange is checked against a fresh full raw
objective and validator; final outputs are checked by the C++ judge. The code
is separate from the original Local Search. It does not implement transfers,
new replication, or arbitrary multi-video replacement.

The bounded results show a real but expensive benefit. From the best tuned
OCAG placement on `videos_worth_spreading`, two top-20 exchange phases and
four full-video exchange phases, each followed by the original Local Search,
reach **610023**. The last full phase still hit its 20-round cap while finding
positive moves, so this is not a combined-neighborhood optimum. The measured
sum of search stages is about 248.1 seconds, versus about 1.74 seconds for
tuned Local Search alone; adding the separate OCAG construction median gives
about 248.4 seconds. This extended pipeline was executed once and then
reproduced from scratch at the same official score; its runtime is not a
repeated median. [`local-search-extension-results.csv`](local-search-extension-results.csv)
records each stage. Starting instead from tuned Greedy, ten full exchange rounds
plus retuned Local Search reach 609698.

On `kittens`, a 20000-pair, all-stored-video, one-round exchange probe found a
positive raw move without changing the rounded score. A further original
Local Search pass produced **1022005**, one point above tuned OCAG + LS, at
about 37.2 seconds of exchange search plus 3.6 seconds of follow-up Local
Search. This is a bounded sample of 20000 of 124750 cache pairs; no full
cross-cache optimality is claimed. Greedy + the smaller exchange probe and
follow-up search stayed at 1021738. On `trending_today`, a 4950-pair top-20
probe found no positive exchange; on `me_at_the_zoo`, all 45 cache pairs and
all stored videos found none. Dynamic OCAG regret was not pursued: static OCAG
still gives the best starting placement on the affected instances, and a
dynamic priority would need a new correct queue maintenance rule.

## Final comparison

`Tuned LS` is the best run using only the original insertion/replacement
neighborhood. `Extended LS` adds the bounded optional exchange when it produced
a useful result. Times combine separately measured construction and search
phases; they exclude parsing, serialization, validation and judge execution.
The extended times are single-run stage sums, not medians.

| Dataset | Previous best | Tuned LS | Extended LS | Final best | Gain | Approx. final algorithm time |
|---|---:|---:|---:|---:|---:|---:|
| me_at_the_zoo | 507906 | 507906 | N/A | 507906 | 0 | 0.015 s (Greedy only) |
| videos_worth_spreading | 609323 | 609635 | 610023 | 610023 | +700 (0.114882%) | 248.4 s |
| trending_today | 499851 | 499857 | 499857 | 499857 | +6 (0.001200%) | 2.923 s |
| kittens | 1021997 | 1022004 | 1022005 | 1022005 | +8 (0.000783%) | 78.5 s |

Final [output files](best-solutions/) were independently judged again after
archiving. Previously validated files were kept under [tuned-solutions](tuned-solutions/)
and the original scientific CSVs were not overwritten. The compact
[`local-search-final-results.csv`](local-search-final-results.csv) is suitable
for a presentation table.

## Reproduction

Run from the repository root with Java 17+, Python 3 and a C++ compiler:

```powershell
.\test.ps1
.\reproduce-local-search-records.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
```

The second command reconstructs the four final placements, checks every
exchange's exact raw delta, and checks the four final outputs with the official
judge. It takes several minutes because the medium extended search scans all
cache pairs for many rounds. Reproduce only the shorter original-neighborhood
finalists with:

```powershell
.\measure-local-search-winners.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
```

[`local-search-tuning-results.csv`](local-search-tuning-results.csv) is also a
replay plan: `run-local-search-tuning.ps1` can recreate and judge its tested
configurations. The generated outputs go under ignored `tmp/`.

## Limitations and presentation points

- The original three-pass cap saved time but left measurable gains on two
  instances. Increasing passes alone raised OCAG + LS to 609508 and 1022000.
- Search path choices matter by dataset: first improvement wins on the medium
  instance, while reverse-order best improvement wins on `kittens`.
- A no-improvement pass establishes a local optimum only for the explicitly
  scanned one-cache insertion/replacement neighborhood and the current solution.
- The exchange step is costly and its final medium run stopped at a round cap.
  It adds a useful but benchmark-specific score gain; it is not the CLI default.
- Candidate-pair sampling on `kittens` limits what can be concluded about
  compound moves. Further rounds or different compound neighborhoods might
  score higher, but would require additional budget and validation.
- Per-instance tuning and the four official inputs do not establish behavior
  on unseen datasets. Timing on one Windows host has normal JVM variation.
- The strongest demonstration is the two-cache synthetic case: Greedy and
  one-cache Local Search stop at 549500, while a coordinated swap reaches the
  exact optimum 925000. On official data, the measurable compound gain is
  concentrated on `videos_worth_spreading`.
