# Twenty-pass Local Search benchmark

Experiment date: 2026-10-18. This follow-up changes only `LocalSearchSolver.MAX_PASSES` from 3 to 20. It retains cache ordering, tie-breaking, neighborhoods, and strict positive-delta acceptance. The limit also applies to OCAG + Local Search, although this benchmark studies Greedy + Local Search.

## Method

- Hardware: AMD Ryzen 5 3600X; Windows 11; Oracle Java 17.0.12; CPU execution.
- Four official input files; baseline, greedy, Greedy + LS(3), and Greedy + LS(20); three repetitions each, 48 runs total.
- Sequential execution, fresh JVM per run, 4 GB maximum heap, 600-second timeout. Variant order rotates between repetitions; no separate JVM warm-up.
- Both budgets use the same snapshot of the actual working-tree Java sources. The isolated 3-pass control changes only the constant. Production sources are never edited by the benchmark driver.
- Scores use the repository implementation of the official scoring formula. Every output passed SolutionValidator. The external official judge was not run.
- Algorithm time includes construction plus repair, excluding parsing, validation/scoring, serialization and JVM startup. Worker wall time is recorded separately.
- Tables report medians of three runs. Ranges are observed minima and maxima, not confidence intervals. Peak memory was not measured.
- All repetitions returned identical scores, accepted-move counts and termination states for each variant. The first three passes have identical accepted moves in both budgets, ignoring elapsed timestamps.

## Scores

| Dataset | Baseline | Greedy | Greedy + LS(3) | Greedy + LS(20) | LS(20) - Greedy | LS(20) - LS(3) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | 330,592 | 507,906 | 507,906 | 507,906 | +0 | +0 |
| videos_worth_spreading | 464,407 | 608,302 | 609,221 | 609,367 | +1,065 | +146 |
| trending_today | 25,791 | 499,794 | 499,851 | 499,851 | +57 | +0 |
| kittens | 53,940 | 1,021,681 | 1,021,735 | 1,021,738 | +57 | +3 |

## Algorithm time

Seconds; median [minimum, maximum].

| Dataset | Baseline | Greedy | Greedy + LS(3) | Greedy + LS(20) |
| --- | ---: | ---: | ---: | ---: |
| me_at_the_zoo | 0.011 [0.010, 0.012] | 0.016 [0.015, 0.016] | 0.025 [0.024, 0.026] | 0.027 [0.023, 0.027] |
| videos_worth_spreading | 0.047 [0.045, 0.052] | 0.377 [0.344, 0.391] | 0.619 [0.613, 0.642] | 0.899 [0.841, 0.900] |
| trending_today | 0.058 [0.056, 0.059] | 3.385 [3.340, 3.668] | 3.721 [3.603, 3.783] | 3.790 [3.594, 3.857] |
| kittens | 0.096 [0.094, 0.110] | 48.266 [38.501, 54.282] | 45.041 [42.131, 81.884] | 45.202 [44.084, 45.931] |

Construction time varied substantially between fresh JVMs, especially on kittens. For example, the 3-pass kittens total ranges from 42.131 to 81.884 seconds. The observed 20-pass total median is below the pure-greedy median; this is not evidence that adding repair accelerates greedy. Compare the separately measured repair phase to assess the additional work.

## Repair cost and convergence

| Dataset | LS(3) repair median (s) | LS(20) repair median (s) | Actual passes at limit 20 | Additions | Replacements | Total accepted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | 0.009 | 0.008 | 1 | 0 | 0 | 0 |
| videos_worth_spreading | 0.313 | 0.568 | 12 | 14 | 155 | 169 |
| trending_today | 0.405 | 0.425 | 2 | 5 | 8 | 13 |
| kittens | 3.622 | 5.853 | 8 | 20 | 411 | 431 |

All 20-pass runs terminate with `no_improving_move`; none uses the full budget. The last pass confirms that no move was accepted. This establishes a local optimum for the implemented addition/one-for-one replacement neighborhood on these inputs, not a global optimum.

- **videos_worth_spreading:** last improving pass 11, confirmation pass 12. Increasing the budget adds 52 accepted moves and 146 score points beyond LS(3). Total improvement over greedy is 1,065 points (0.1751%). Repair median rises from 0.313 to 0.568 seconds.
- **kittens:** last improving pass 7, confirmation pass 8. Increasing the budget adds 48 accepted moves and only 3 integer score points beyond LS(3). Total improvement over greedy is 57 points (0.0056%). Repair median rises from 3.622 to 5.853 seconds. Passes 6 and 7 still improve raw saved latency even though the displayed score remains unchanged.
- **me_at_the_zoo:** no improving move in pass 1; the larger limit changes neither the placement nor score.
- **trending_today:** 13 moves in pass 1, none in pass 2; the larger limit changes neither the placement nor score.

## Per-pass detail

The following records are from repetition 1 of the 20-pass variant. All three repetitions have identical aggregate search results. Full accepted-move traces and all per-pass records are linked below.

| Dataset | Pass | Accepted moves | Raw gain (request-ms) | Score after pass |
| --- | ---: | ---: | ---: | ---: |
| videos_worth_spreading | 1 | 53 | 230,805,387 | 608,764 |
| videos_worth_spreading | 2 | 33 | 145,598,950 | 609,056 |
| videos_worth_spreading | 3 | 31 | 82,370,901 | 609,221 |
| videos_worth_spreading | 4 | 25 | 30,982,411 | 609,283 |
| videos_worth_spreading | 5 | 10 | 18,877,306 | 609,320 |
| videos_worth_spreading | 6 | 4 | 9,597,114 | 609,340 |
| videos_worth_spreading | 7 | 2 | 5,716,071 | 609,351 |
| videos_worth_spreading | 8 | 3 | 3,319,405 | 609,358 |
| videos_worth_spreading | 9 | 3 | 3,602,941 | 609,365 |
| videos_worth_spreading | 10 | 4 | 814,420 | 609,366 |
| videos_worth_spreading | 11 | 1 | 341,242 | 609,367 |
| videos_worth_spreading | 12 | 0 | 0 | 609,367 |
| kittens | 1 | 211 | 35,970,397 | 1,021,717 |
| kittens | 2 | 111 | 11,808,980 | 1,021,729 |
| kittens | 3 | 61 | 6,467,686 | 1,021,735 |
| kittens | 4 | 29 | 1,832,463 | 1,021,737 |
| kittens | 5 | 14 | 612,128 | 1,021,738 |
| kittens | 6 | 3 | 214,942 | 1,021,738 |
| kittens | 7 | 2 | 54,210 | 1,021,738 |
| kittens | 8 | 0 | 0 | 1,021,738 |

## Interpretation

Baseline is the fastest construction but its placements score substantially worse on all four inputs. Endpoint-aware greedy provides the main quality improvement. Local Search refines that construction at additional CPU cost. Raising the limit fixes premature budget stops in two cases and yields diminishing gains; its usefulness differs by input.

For these four inputs, increasing the pass limit beyond 20 would have no effect because the algorithm has already stopped early. Further quality research should study a broader neighborhood or different initial placements. That is a hypothesis for future work; no such algorithm was implemented or benchmarked here. The study contains four official inputs and three timing samples per variant, so it does not establish universal performance or results on generated/online workloads.

## Reproduce and inspect

```powershell
python .\benchmark-local-search.py --output-dir .\experiments\my-ls20-study
```

Set JAVA_HOME to a Java 17+ JDK. The output directory must be new. Raw source snapshots, compiled classes, validated solutions, per-run metrics and logs are kept in the ignored temporary directory recorded in configuration.json.

- [Configuration, source/input hashes, and validation](configuration.json)
- [All 48 runs and phase timings](runs.csv)
- [Summary with medians and ranges](summary.csv)
- [Per-pass gains](passes.csv)
- [Worth accepted moves](videos_worth_spreading.greedy-ls-20.trace.csv)
- [Kittens accepted moves](kittens.greedy-ls-20.trace.csv)

Validation: existing solver regressions passed, five Python comparison tests passed, and eight web tests passed against a freshly compiled isolated source copy. The initial web test attempt hit AccessDeniedException during atomic replacement in the sandbox system temp directory; moving the test temp directory inside the workspace resolved it without a production code change.

The running web service was not restarted and the deployed JAR was not replaced. Source changes take effect after rebuilding and restarting that deployment. Historical experiments and their three-pass configuration remain unchanged.
