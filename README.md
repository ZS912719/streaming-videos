# Streaming Videos - Google Hash Code 2017

This repository solves the Google Hash Code 2017 **Streaming Videos** problem. Videos must be placed in capacity-limited caches so requests are served with less latency than from the data center. The official score is the average saved latency, multiplied by 1,000.

## Web Workspace

Build the optional Spring Boot module with Maven 3.6.3+ and Java 17:

```powershell
mvn -f web/pom.xml package
java -Xmx512m -jar web/target/streaming-web.jar
```

Run these commands from the project root and open `http://127.0.0.1:8080`.
Drag an `.in` file into the upload area to create a session. The page shows
dataset dimensions, request counts, connections and sparse contribution counts.
Five strategies run sequentially in separate Java processes: `baseline`,
`greedy`, `ocag`, `local-search` (greedy + Local Search), and `ocag-ls`.
Each worker has a 2 GB heap and a 300-second timeout, and reuses the scientific
experiment runner rather than a separate solver implementation. Each solver has its own score,
runtime, output preview and full output download. Completed cards show the
score difference relative to greedy and highlight the best score, including ties.
New sessions also expose construction and Local Search timings, the initial
score before repair, accepted moves, stop reason and OCAG regret statistics.
Static regret is a priority signal, not additional realized score. Download
worker logs, metrics CSVs and applicable construction/repair traces from each card.
Failed or timed-out solvers
are reported separately and do not prevent later solvers from running.

Inputs, results and logs are saved under `data/sessions` (ignored by Git).
History is shared by users of this local server and persists across restarts.
Older sessions keep their original solver lists and may lack phase metrics;
upload the input again to run the current comparison. Algorithm timings exclude
startup, parsing, validation/scoring and output. Worker runtime includes those
costs. The three-pass Local Search limit does not guarantee convergence.
Interrupted runs are marked as interrupted on restart. The queue holds eight
waiting sessions. Uploads are limited to 32 MB and checked against the problem
dimension and request bounds. To support `examples/opportunity-cost.in`, cache
latencies may exceed the official 500 ms bound, but must remain positive and
strictly below the endpoint's data center latency (at most 4,000 ms).
The app binds to localhost and is intended for local experiments.
Change `--server.port`, `--streaming.sessions`, or `--streaming.timeout-seconds`
on the launch command to override defaults. Keep the packaged worker JAR in
`web/target/streaming-web.jar` and run from the project root.

After source updates, stop the existing service, rebuild with `mvn -f web/pom.xml
package`, then restart using the same port and session directory. Existing
packaged JARs and running processes do not update automatically. For a Tailscale
deployment, retain the existing Serve configuration if it forwards to localhost;
no additional public binding or port exposure is needed for this update.

Web regression tests (`mvn -f web/pom.xml test`) verify all five worker strategies,
scientific metrics/trace generation, artifact download allowlisting, experimental
input validation and legacy-session compatibility.

## Solver Algorithms

`baseline` ranks videos globally by `total requests / video size` and fills every cache from the same ranking. It is intentionally simple and ignores endpoint-specific latency and overlap between caches.

`greedy` repeatedly selects the cache/video pair with the greatest current marginal latency saving per MB:

```text
marginalGain(c, v) = sum requests(v, e) * max(0, currentBestLatency(v, e) - cacheLatency(c, e))
priority(c, v)     = marginalGain(c, v) / videoSize(v)
```

After a placement, affected best latencies are updated. A lazy priority queue avoids eagerly recalculating every candidate. Candidate contributions are stored in compact primitive arrays.

`local-search` starts from the greedy placement and improves it using additions
or one-for-one video replacements within a cache. Every accepted move has a
strictly positive change in total request-weighted saved latency. Removal losses
are evaluated with the selected cache excluded, so copies in other caches are
accounted for exactly. The search accepts at most one move per cache per pass
and runs up to three passes, stopping earlier when an entire pass has no move.
It preserves capacity constraints but can stop before reaching a local optimum.
It does not consider replacing multiple videos at once. Evaluating neighboring
placements adds CPU cost, particularly on dense topologies.

`ocag` is the separate Opportunity-Cost Aware Greedy construction. It augments
current marginal gain with a fixed, parameter-free alternative-placement regret:

```text
regret(v,c) = max(0, initialGain(v,c) - bestInitialGain(v, another cache))
priority(v,c) = (currentMarginalGain(v,c) + regret(v,c)) / videoSize(v)
```

This makes a uniquely good cache for a video more urgent when that video has no
comparable alternative. The regret remains fixed, so the lazy queue still uses
valid upper bounds. The original `greedy` implementation is unchanged.

## Build

Java 17 or newer is required for the solver. Python 3.9 or newer is recommended
for the experiment generator. A C++ compiler is needed only when building the
external official judge. No third-party Java or Python libraries are required.

Build only the solver:

```powershell
.\build.ps1
```

Build the solver and the supplied official judge by passing its location:

```powershell
.\build.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
```

## Run

```powershell
java -cp out com.hashcode.streaming.Main greedy examples\me_at_the_zoo.in tmp\zoo.out
java -cp out com.hashcode.streaming.Main baseline examples\me_at_the_zoo.in tmp\zoo-baseline.out
java -Xmx4g -cp out com.hashcode.streaming.Main local-search examples\me_at_the_zoo.in tmp\zoo-local.out
java -Xmx4g -cp out com.hashcode.streaming.Main ocag examples\me_at_the_zoo.in tmp\zoo-ocag.out
```

The original two-argument syntax remains shorthand for `greedy`:

```powershell
java -cp out com.hashcode.streaming.Main examples\me_at_the_zoo.in tmp\zoo.out
```

## Validate with the official judge

After building the judge:

```powershell
.\tmp\judgeHashCode2017.exe examples\me_at_the_zoo.in tmp\zoo.out
```

## Benchmark

### Compare all solvers on your own datasets

With Python 3.9+ and a Java 17+ JDK (set `JAVA_HOME` to the JDK, or put
`java` and `javac` on PATH), run
from the repository root:

```powershell
python .\compare-solvers.py "C:\datasets\my-data.in"
python .\compare-solvers.py "C:\datasets\first.in" "C:\datasets\second.in"
```

The script builds the current Java sources in its own result directory and
automatically discovers every strategy registered in `SolverRegistry`:
`baseline`, `greedy`, `ocag`, `local-search`, and `ocag-ls`. No Maven or external
judge is required. Each solution is capacity-validated and scored with the
repository's implementation of the official scoring formula.

Default execution is sequential for comparable timings. To run workers
concurrently, choose the number of workers explicitly:

```powershell
python .\compare-solvers.py "C:\datasets\my-data.in" --jobs 2 --heap 2g --timeout 600
python .\compare-solvers.py .\examples\local-search.in --output-dir .\tmp\my-comparison
python .\compare-solvers.py .\examples\example.in --algorithms greedy local-search
```

`--heap` is the heap limit **per worker**; concurrent workers share CPU and RAM,
so parallel timings reflect contention. The default timeout is 300 seconds per
run and the default heap is 2 GB. Paths supplied by the user are resolved from
the current working directory, including paths containing spaces.

Results are saved in a unique `tmp/comparisons/<timestamp>-<suffix>/` directory,
or the new directory specified by `--output-dir`. Existing output directories
are rejected to preserve previous results. `comparison.csv` contains scores,
score ranks (ties share a rank), differences from greedy, solver-only timing
(`solve_ms`), worker timing (`worker_seconds`, including startup, parsing,
validation/scoring and output), nonempty cache counts, statuses and artifact
paths. `dataset-001/`, `dataset-002/`, etc. keep each dataset's solutions,
metrics and logs separate even when input filenames are identical. Failed or
timed-out solvers do not stop the remaining comparisons; failed solutions are
discarded. Exit codes: 0 = all succeeded, 1 = at least one run failed or timed
out, 2 = invalid arguments or build failure.

To add a solver, implement a class under
`src/main/java/com/hashcode/streaming/solver/` that returns a `Solution` for a
`ProblemInstance`, then add a registration in `SolverRegistry`:

```java
solvers.put("my-solver", problem -> new MySolver().solve(problem));
```

Use a unique filename-safe name consisting of letters, digits and hyphens.
Construct a fresh solver inside the registration lambda so runs do not share
mutable solver state. The comparison script discovers the new entry on its next
run, and `Main` also accepts the registered name. Web strategy lists and the
scientific study's phase-specific algorithms remain configured separately.

Run both algorithms on all four official datasets and create `benchmark-results.csv`:

```powershell
.\benchmark.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
```

The CSV contains the dataset, algorithm, official judge score, and end-to-end runtime. The comparison shows why endpoint-aware dynamic marginal gains outperform a global popularity ranking, while keeping the experiment reproducible and easy to demonstrate.

The benchmark script currently runs `baseline` and `greedy`. Run `local-search`
separately using the command above; the existing CSV does not include it.

## Scientific Study

The controlled study compares construction (`greedy`, `ocag`) with repair
(`greedy-ls`, `ocag-ls`), includes Local Search ablations and exact tiny cases,
and evaluates fixed placements under paired demand perturbations. Its full
interpretation is in [`experiments/SCIENTIFIC_REPORT.md`](experiments/SCIENTIFIC_REPORT.md).

Run the fast regression checks:

```powershell
.\test.ps1
```

Reproduce the official score/runtime experiments and their CSV summaries:

```powershell
.\run-scientific-experiments.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
python .\summarize-experiments.py
```

Reproduce the paired robustness study. It constructs every placement only from
the nominal instance and judge-checks representative perturbed scenarios:

```powershell
.\run-robustness-experiments.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
```

Raw measurements and compact summaries are versioned under `experiments/`;
generated solutions, judge binaries and per-run temporary files stay under
the ignored `tmp/` directory.

## Local Search Regression Case

`examples/local-search.in` has one 10 MB cache and two videos of sizes 6 MB and
10 MB. Density greedy selects the 6 MB video, leaving too little space for the
other video. Local search replaces it with the 10 MB video, increasing the score
from 370,588 to 529,411. Both placements satisfy the capacity constraint.

```powershell
java -cp out com.hashcode.streaming.Main greedy examples/local-search.in tmp/swap-greedy.out
java -cp out com.hashcode.streaming.Main local-search examples/local-search.in tmp/swap-local.out
```

## Generate Experimental Datasets

The standard-library Python generator targets a Ryzen 5 3600X with 16 GB RAM.
The Java solvers use the CPU; the RTX 3060 Ti is not used. Run one solver at a
time with `-Xmx4g`. Memory estimates describe index arrays, not total heap usage.

```powershell
python generate-experiments.py
python generate-experiments.py --seeds 17 --only reference scale_small --output-dir tmp/smoke
python generate-experiments.py --include-stress --output-dir tmp/stress-suite
```

The default suite contains 10 scenarios for each of 3 seeds (30 files):

The default seeds are 17, 29, and 43. Input files are named
`<scenario>.seed_<seed>.in` and written to `tmp/experiments` by default.

| Scenario | Videos | Endpoints | Caches | Request descriptions | Cache links per connected endpoint |
| --- | ---: | ---: | ---: | ---: | ---: |
| Reference and factor variants | 2,000 | 100 | 60 | 20,000 | 2, 8, or 24 |
| Small scale | 500 | 30 | 20 | 3,000 | 4 |
| Large scale | 5,000 | 300 | 150 | 80,000 | 12 |
| Optional stress | 10,000 | 500 | 250 | 150,000 | 24 |

Reference capacity is 5,000 MB, Zipf exponent is 1.0, and degree is 8.
Capacity variants use 1,250, 2,500, and 10,000 MB. Popularity variants use
Zipf exponents 0.6 and 1.4. Connectivity variants use degrees 2 and 24.
Only the named factor changes relative to the reference for a given seed.
Scale scenarios change several size parameters together and measure overall
scaling rather than attributing cost to a single parameter.

Video sizes are uniformly sampled from 10 to 1,000 MB. Video popularity ranks
are shuffled independently of sizes. Requests use a Zipf distribution, uniform
endpoint selection, and counts from 1 to 10,000. Duplicate endpoint/video
descriptions are intentional and are aggregated by the input parser. About 5%
of endpoints have no cache links. Data center latencies range from 600 to 4,000
ms and cache latencies from 1 to 500 ms. Links are distinct within each endpoint.
Density variants share prefix links; capacity variants have identical content,
topology, and requests. These are synthetic aggregate workloads without a
temporal request model, so they do not establish online-cache performance.

Each run writes ASCII input files with LF endings, a `manifest.csv` containing
parameters, hashes, exact unique demand/contribution counts and array memory
estimates, and a `configuration.json`. Existing nonempty directories are rejected
to preserve previous experiments. The optional stress case has at most 3.6
million contributions and 2.5 million possible cache/video pairs. Runtime still
depends on lazy queue updates; these limits are not a runtime guarantee.

```powershell
java -Xmx4g -cp out com.hashcode.streaming.Main greedy tmp/experiments/reference.seed_17.in tmp/reference.out
```
