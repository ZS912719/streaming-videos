# Streaming Videos - Google Hash Code 2017

This repository solves the Google Hash Code 2017 **Streaming Videos** problem. Videos must be placed in capacity-limited caches so requests are served with less latency than from the data center. The official score is the average saved latency, multiplied by 1,000.

## Algorithms

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
.\build.ps1 -JudgeSource "..\judgeHashCode2017.cpp"
```

## Run

```powershell
java -cp out com.hashcode.streaming.Main greedy examples\me_at_the_zoo.in tmp\zoo.out
java -cp out com.hashcode.streaming.Main baseline examples\me_at_the_zoo.in tmp\zoo-baseline.out
java -Xmx4g -cp out com.hashcode.streaming.Main local-search examples\me_at_the_zoo.in tmp\zoo-local.out
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

Run both algorithms on all four official datasets and create `benchmark-results.csv`:

```powershell
.\benchmark.ps1 -JudgeSource "..\judgeHashCode2017.cpp"
```

The CSV contains the dataset, algorithm, official judge score, and end-to-end runtime. The comparison shows why endpoint-aware dynamic marginal gains outperform a global popularity ranking, while keeping the experiment reproducible and easy to demonstrate.

The benchmark script currently runs `baseline` and `greedy`. Run `local-search`
separately using the command above; the existing CSV does not include it.

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
