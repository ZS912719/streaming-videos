# Streaming Videos — Google Hash Code 2017

This repository solves the Google Hash Code 2017 **Streaming Videos** problem. Videos must be placed in capacity-limited caches so requests are served with less latency than from the data center. The official score is the average saved latency, multiplied by 1,000.

## Algorithms

`baseline` ranks videos globally by `total requests / video size` and fills every cache from the same ranking. It is intentionally simple and ignores endpoint-specific latency and overlap between caches.

`greedy` repeatedly selects the cache/video pair with the greatest current marginal latency saving per MB:

```text
marginalGain(c, v) = sum requests(v, e) * max(0, currentBestLatency(v, e) - cacheLatency(c, e))
priority(c, v)     = marginalGain(c, v) / videoSize(v)
```

After a placement, affected best latencies are updated. A lazy priority queue avoids eagerly recalculating every candidate. Candidate contributions are stored in compact primitive arrays.

## Build

Java 17 or newer and a C++ compiler are sufficient; no external libraries are used.

Build only the solver:

```powershell
.\build.ps1
```

Build the solver and the supplied official judge by passing its location:

```powershell
.\build.ps1 -JudgeSource "..\Consignes et autres\judgeHashCode2017.cpp"
```

## Run

```powershell
java -cp out com.hashcode.streaming.Main greedy examples\me_at_the_zoo.in tmp\zoo.out
java -cp out com.hashcode.streaming.Main baseline examples\me_at_the_zoo.in tmp\zoo-baseline.out
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
.\benchmark.ps1 -JudgeSource "..\Consignes et autres\judgeHashCode2017.cpp"
```

The CSV contains the dataset, algorithm, official judge score, and end-to-end runtime. The comparison shows why endpoint-aware dynamic marginal gains outperform a global popularity ranking, while keeping the experiment reproducible and easy to demonstrate.
