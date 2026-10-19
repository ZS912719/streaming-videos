# Equal-budget one- and two-removal Local Search

Experiment date: 2026-10-18. This study adds a separate bounded neighborhood: add one absent video and remove zero, one, or two stored videos from the same cache. The original one-removal strategy remains available. Both variants start from the exact same serialized Greedy placement and receive the same repair-time allowance.

## Main result: 10-second allowance

Each value below is the median of three fresh JVM runs. Repair times are actual elapsed seconds. An accepted move can be an insertion, one-for-one replacement, or two-for-one replacement; removing two videos counts as one move.

| Dataset | One: score | Two: score | Score difference | One: seconds | Two: seconds | One: accepted | Two: accepted | Two-for-one moves |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | 507,906 | 512,115 | +4,209 | 0.013 | 0.017 | 0 | 3 | 1 |
| videos_worth_spreading | 609,367 | 609,889 | +522 | 0.973 | 0.789 | 169 | 309 | 169 |
| trending_today | 499,851 | 499,851 | +0 | 0.355 | 0.476 | 13 | 13 | 0 |
| kittens | 1,021,738 | 1,022,894 | +1,156 | 6.279 | 10.005 | 431 | 1,695 | 816 |

At 10 seconds, the extended variant wins all three paired repetitions on Zoo, Worth and Kittens, and ties all three on Trending. This is evidence for these four official inputs, not a universal guarantee. Kittens with two removals is still budget-limited; its result is not a demonstrated local optimum.

## Implementation

The implementation reuses the existing best/second-best request latencies. For cache c, B(c,v) is the exact contribution of storing v relative to excluding c. For absent video v and removed set R:

```text
netGain = B(c,v) - sum(B(c,u) for u in R)
capacity: size(v) <= freeSpace(c) + sum(size(u) for u in R)
|R| in {0,1} for the original neighborhood
|R| in {0,1,2} for the extended neighborhood
```

Different videos have separate requests, so the removal losses are additive even with copies in other caches. Every accepted delta is strictly positive in raw request-milliseconds; the displayed integer score need not increase after every move.

For each cache, the new implementation collects distinct required-space thresholds from absent videos, enumerates all stored singles and distinct pairs, and records the lowest removal loss at the largest threshold each option satisfies. A suffix minimum then answers every required-space query. Pairs are considered even if a feasible single exists. No top-K truncation or arbitrary pair sampling is used. Equal losses prefer fewer removals and then video IDs.

For K stored videos and D distinct space requirements, the removal-option preprocessing takes O(K squared times log D), plus threshold sorting. Its auxiliary table has O(videoCount) memory; it does not retain all pair objects. The current one-removal comparator keeps its original linear scan through stored videos. Thus this is a comparison of the implemented strategies: timing changes can reflect both the larger neighborhood and the new loss lookup, not neighborhood size alone. Peak memory was not measured.

Both versions visit caches by ID and accept at most one best move per cache per pass. The timed API has no practical pass cap, so both variants can use the full allowance until no improving move remains. The ordinary local-search and new local-search-two CLI strategies still default to the 20-pass cap. The web strategy list is unchanged.

## Method and timing limits

- AMD Ryzen 5 3600X, Windows 11, Oracle Java 17.0.12, CPU only, 4 GB maximum Java heap.
- Four official datasets, repair budgets of 1, 5 and 10 seconds, two variants, three repetitions: 72 runs.
- A fresh Greedy seed is built once per dataset and reused byte-for-byte in both variants. Source, input and seed hashes are saved in configuration.json.
- All measured workers run sequentially in fresh JVMs. Variant order alternates across budgets and repetitions. There is no separate JVM warm-up.
- The repair timer includes search-index construction, neighborhood evaluation, accepted moves and statistics collection. It excludes input/seed reading, Greedy construction, independent scoring/validation, output and JVM startup. Worker wall time is also recorded separately.
- Both variants use the same cooperative deadline checks. An unfinished cache evaluation is discarded. Initialization is not interruptible; a committed move is completed atomically. Therefore these are equal nominal allowances, not hard real-time deadlines. Actual time and overrun are reported for every run.
- In Kittens at 1 second, initialization exhausts the allowance before either variant accepts a move. Actual repair times range from 1.017 to 1.301 seconds. Both return the original 1,021,681-point seed. This budget does not compare productive search work on that input.
- Scores are computed with the repository implementation of the official formula, and every final solution is capacity-validated. The external official judge was not run.
- Ranges below are observed minimum/maximum values from three runs, not confidence intervals. Timed runs can accept different move counts due to JVM and scheduling variability.

## Full benchmark

Cells show median [minimum, maximum], omitting the range for identical integer values. Converged/budget gives the number of repetitions ending with no improving move versus the time limit.

### 1-second allowance

| Dataset | Variant | Score | Actual repair seconds | Accepted moves | Two-for-one moves | Converged / budget |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | one | 507,906 | 0.012 [0.012, 0.013] | 0 | 0 | 3 / 0 |
| me_at_the_zoo | two | 512,115 | 0.016 [0.014, 0.019] | 3 | 1 | 3 / 0 |
| videos_worth_spreading | one | 609,367 | 0.921 [0.880, 1.005] | 169 | 0 | 2 / 1 |
| videos_worth_spreading | two | 609,889 | 0.685 [0.682, 0.779] | 309 | 169 | 3 / 0 |
| trending_today | one | 499,851 | 0.363 [0.342, 0.367] | 13 | 0 | 3 / 0 |
| trending_today | two | 499,851 | 0.434 [0.404, 0.569] | 13 | 0 | 3 / 0 |
| kittens | one | 1,021,681 | 1.117 [1.033, 1.204] | 0 | 0 | 0 / 3 |
| kittens | two | 1,021,681 | 1.285 [1.017, 1.301] | 0 | 0 | 0 / 3 |

### 5-second allowance

| Dataset | Variant | Score | Actual repair seconds | Accepted moves | Two-for-one moves | Converged / budget |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | one | 507,906 | 0.016 [0.013, 0.016] | 0 | 0 | 3 / 0 |
| me_at_the_zoo | two | 512,115 | 0.015 [0.014, 0.017] | 3 | 1 | 3 / 0 |
| videos_worth_spreading | one | 609,367 | 0.868 [0.776, 1.044] | 169 | 0 | 3 / 0 |
| videos_worth_spreading | two | 609,889 | 0.836 [0.718, 0.945] | 309 | 169 | 3 / 0 |
| trending_today | one | 499,851 | 0.381 [0.367, 0.478] | 13 | 0 | 3 / 0 |
| trending_today | two | 499,851 | 0.419 [0.393, 0.538] | 13 | 0 | 3 / 0 |
| kittens | one | 1,021,738 [1,021,737, 1,021,738] | 5.005 [5.004, 5.007] | 426 [410, 426] | 0 | 0 / 3 |
| kittens | two | 1,022,742 [1,022,662, 1,022,751] | 5.004 [5.004, 5.004] | 1,210 [1,031, 1,239] | 672 [611, 685] | 0 / 3 |

### 10-second allowance

| Dataset | Variant | Score | Actual repair seconds | Accepted moves | Two-for-one moves | Converged / budget |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | one | 507,906 | 0.013 [0.013, 0.014] | 0 | 0 | 3 / 0 |
| me_at_the_zoo | two | 512,115 | 0.017 [0.017, 0.023] | 3 | 1 | 3 / 0 |
| videos_worth_spreading | one | 609,367 | 0.973 [0.816, 0.997] | 169 | 0 | 3 / 0 |
| videos_worth_spreading | two | 609,889 | 0.789 [0.785, 1.002] | 309 | 169 | 3 / 0 |
| trending_today | one | 499,851 | 0.355 [0.354, 0.363] | 13 | 0 | 3 / 0 |
| trending_today | two | 499,851 | 0.476 [0.451, 0.503] | 13 | 0 | 3 / 0 |
| kittens | one | 1,021,738 | 6.279 [6.221, 7.364] | 431 | 0 | 3 / 0 |
| kittens | two | 1,022,894 [1,022,894, 1,022,895] | 10.005 [10.004, 10.005] | 1,695 [1,691, 1,697] | 816 | 0 / 3 |

## Paired score differences

Difference means two minus one in the matching repetition. Its median is computed from paired differences, rather than assuming a difference between marginal medians is always equivalent.

| Dataset | Budget seconds | Median difference | Minimum | Maximum | Wins / ties / losses for two |
| --- | ---: | ---: | ---: | ---: | ---: |
| me_at_the_zoo | 1 | +4,209 | +4,209 | +4,209 | 3 / 0 / 0 |
| me_at_the_zoo | 5 | +4,209 | +4,209 | +4,209 | 3 / 0 / 0 |
| me_at_the_zoo | 10 | +4,209 | +4,209 | +4,209 | 3 / 0 / 0 |
| videos_worth_spreading | 1 | +522 | +522 | +522 | 3 / 0 / 0 |
| videos_worth_spreading | 5 | +522 | +522 | +522 | 3 / 0 / 0 |
| videos_worth_spreading | 10 | +522 | +522 | +522 | 3 / 0 / 0 |
| trending_today | 1 | +0 | +0 | +0 | 0 / 3 / 0 |
| trending_today | 5 | +0 | +0 | +0 | 0 / 3 / 0 |
| trending_today | 10 | +0 | +0 | +0 | 0 / 3 / 0 |
| kittens | 1 | +0 | +0 | +0 | 0 / 3 / 0 |
| kittens | 5 | +1,004 | +924 | +1,014 | 3 / 0 / 0 |
| kittens | 10 | +1,156 | +1,156 | +1,157 | 3 / 0 / 0 |

## Interpretation

- **Zoo:** one-removal search accepts no move. Allowing two removals opens a path to 512,115 points with three accepted moves, including one two-for-one replacement. All budgets are ample for convergence.
- **Worth:** the extended search converges at 609,889 points with 309 moves, including 169 two-for-one replacements. The original converges at 609,367 with 169 moves. The extended search is faster in the observed medians, but its precomputed removal-loss queries differ from the original linear scan and samples are few; do not attribute the timing difference solely to a larger neighborhood.
- **Trending:** both versions converge at 499,851 with 13 moves and no two-for-one replacement. Pair preprocessing adds work without a score benefit on this seed.
- **Kittens:** at 5 seconds the paired score improvement is +1,004 median, range +924 to +1,014, with three wins. The extended search accepts 1,210 moves median, including 672 two-for-one moves. At 10 seconds the paired improvement is +1,156 median, range +1,156 to +1,157; extended scores are 1,022,894 to 1,022,895 and it is still budget-limited. The original has already converged with 431 moves.

These results support keeping the new neighborhood as a separate option: it improves three inputs under adequate budgets but adds overhead without improving Trending. A larger neighborhood can change the search trajectory and is not guaranteed to dominate a narrower one under every budget or on every input. Any longer-budget study on Kittens would be new work; this report makes no convergence claim for it.

## Mechanism and verification

The new examples/two-removal.in has a 10 MB cache and videos of sizes 4, 4 and 10 MB, requested 5, 5 and 11 times. Greedy stores the two 4 MB videos. No single removal makes the 10 MB video fit, but removing both gives a positive net gain of 900 request-ms. The registered local-search-two command and scientific greedy-ls-two runner both produce 471,428 points, versus the 428,571-point Greedy seed.

- Existing solver regression checks passed.
- The new test harness checks 40 fixed-seed random placements in both neighborhoods, using an exhaustive per-cache oracle for every accepted move and for any claimed local optimum. It also checks the crafted two-removal case, a cheaper pair despite a feasible single, zero budget, overlapping copies, trace deltas and capacities.
- All 72 benchmark traces were replayed to verify distinct removals, cache capacity after every move, positive gains, cumulative totals, accepted/double counts and equality with the final serialized placement. The worker independently checked each final score against the trace total.
- Five Python comparison tests passed, including discovery of the sixth registered strategy.
- Eight web tests passed against a fresh source copy with its temporary directory inside the workspace.

## Reproduce and artifacts

```powershell
python .\benchmark-neighborhoods.py --output-dir .\experiments\my-neighborhood-study
```

Set JAVA_HOME to a Java 17+ JDK. The output directory must not already exist. Optional arguments include --budgets-ms, --repetitions, --heap and --datasets. The script snapshots and compiles current working-tree sources; it does not rely on existing out or web/target classes.

After building the standalone project, the default 20-pass strategy can be run with:

```powershell
java -cp out com.hashcode.streaming.Main local-search-two examples\two-removal.in tmp\two-removal.out
```

- [Configuration, hashes, seed scores and validation](configuration.json)
- [All 72 runs](runs.csv)
- [Medians, ranges, accepted counts and stop reasons](summary.csv)
- [Paired differences](paired.csv)
- [Kittens, 5-second two-removal trace, repetition 1](kittens.5000ms.two.rep-1.trace.csv)
- [Kittens, 10-second two-removal trace, repetition 1](kittens.10000ms.two.rep-1.trace.csv)

Each budget/variant has its repetition-1 trace in this directory. Every run's trace, validated solution, metrics and log, plus source snapshots and compiled classes, are retained in the ignored raw directory recorded in configuration.json.

Historical three- and twenty-pass measurements are preserved. No Git commit or push was made, and no running service was restarted or deployed.
