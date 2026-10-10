# Scientific Optimization Study

## 1. Research questions

This study asks two primary questions:

1. Is it more effective to anticipate opportunity costs during construction, or to repair greedy decisions afterward with Local Search?
2. Do nominally stronger placements remain stronger when predicted demand differs from evaluated demand?

The hypotheses were stated before interpreting the final measurements:

- H1: the existing Local Search should improve greedy when a profitable one-cache replacement exists, but should be limited by its neighborhood and three-pass budget.
- H2: an alternative-cache regret can prevent some capacity-allocation mistakes before they occur.
- H3: OCAG and Local Search may be complementary because one changes construction and the other repairs a completed placement.
- H4: nominal improvements may not persist under changed request weights.

No claim of universal superiority or global optimality is made for the four official instances.

## 2. Algorithms

### Baseline

The baseline ranks videos globally by total request count divided by video size and fills every cache from the same ranking. It ignores endpoint topology, latency and redundant coverage.

### Dynamic greedy

The original deterministic greedy prioritizes cache/video candidates by current marginal saved latency per MB:

```text
marginalGain(c,v) = sum_e requests(v,e)
                    * max(0, currentBestLatency(v,e) - cacheLatency(c,e))

priority(c,v) = marginalGain(c,v) / videoSize(v)
```

It uses a sparse contribution index, maintained best latency per demand and lazy priority-queue refresh. Stored gains remain upper bounds because placements only reduce later marginal gains. Capacity only decreases, so a candidate that no longer fits can be discarded permanently. Gains and request-weighted objectives use `long`; queue density uses `double`. Candidate order retains the historical `HashMap.computeIfAbsent` order so equal-priority behavior and the four reference scores remain unchanged.

### Existing Local Search

`LocalSearchSolver.solve` first builds the unmodified greedy solution. Its public `improve` method can also receive any independently constructed solution and mutates it afterward.

For each cache, in cache-ID order, it calculates exact benefits with that cache excluded. It then selects the best strictly improving move among:

- insertion of one absent video if remaining capacity permits;
- replacement of one stored video by one absent video if the removed video frees enough capacity.

Stored videos are ordered by exact removal loss, then video ID. For each absent video, the first size-feasible removal therefore has the smallest loss. The best move for that cache is accepted; at most one move is accepted per cache per pass. Caches later in a pass see changes made to earlier caches. The algorithm stops after a pass with no accepted move or after three passes.

This is deterministic, has no seed and accepts only positive exact request-millisecond deltas. Strict improvement prevents revisiting the identical solution, although a previously inserted video can be replaced later. It is best improvement within the restricted move set for one cache, not global best improvement across all caches. It has no transfers, two-cache swaps, multi-removals or multi-video replacements, and the three-pass limit means it can stop before a local optimum.

The original code repeatedly searched every endpoint connection while evaluating every cache. A behavior-preserving index now maintains best and second-best serving latency per demand. This reduces a full pass from a degree-squared style scan to approximately one visit per cache-demand contribution, while preserving every tested score and accepted-move total.

### Opportunity-Cost Aware Greedy

Two candidate formulations were considered:

1. **Alternative-placement regret.** A placement is urgent when its gain is much better than the same video's best other cache.
2. **Capacity shadow price.** A cache is expensive when many high-density candidates compete for its remaining capacity.

A dynamic shadow price was rejected for this study because its value could increase after placements, invalidating the original lazy queue's upper-bound argument unless the queue were substantially redesigned. It would also require a defensible time-varying price model or a tuned coefficient.

The chosen parameter-free formulation uses fixed initial alternative-placement regret:

```text
alternative(v,c) = max initialMarginalGain(v,c') over c' != c
regret(v,c)      = max(0, initialMarginalGain(v,c) - alternative(v,c))

OCAGPriority(v,c) = (currentMarginalGain(v,c) + regret(v,c)) / videoSize(v)
```

Only a uniquely best initial cache for a video has positive regret. The regret estimates the avoidable loss if that preferred placement becomes unavailable and the video must use its best alternative. Regret is fixed while current marginal gain only decreases, so lazy stored priorities remain valid upper bounds. A candidate with zero current marginal gain is still discarded even if its historical regret is positive.

Weaknesses are explicit: initial gains can become stale; gains at different caches can overlap; replication can be useful; regret considers the same video's alternatives but not every competing video; and a large regret does not guarantee a globally good placement. The method is a one-step construction proxy, not an optimal assignment model.

## 3. Experimental protocol

- Instances: the four official datasets plus two tiny synthetic instances.
- Official validation: every timed official placement was checked with `judgeHashCode2017.cpp`; internal and official scores matched.
- Timing: algorithm time excludes parsing, validation/scoring, serialization and judge execution.
- Repetitions: five fresh-JVM runs for the first three datasets and three for `kittens`.
- Summary: median runtime with observed minimum and maximum.
- JVM: Oracle Java 21.0.10, sources compiled with `--release 17`, `-Xmx4g`.
- Platform: Windows 10 Home, AMD64. The available processor identifier is recorded in `experiment-config.json`.
- Determinism: all optimization algorithms are deterministic; every repeated score was identical.
- Memory: reliable peak working-set data was not available from the execution host, so no measured memory claim is made. The implementation uses primitive arrays and a 4 GB heap limit.
- Robustness: 30 paired seeds per level and model. Placements are built once on nominal demand and never reoptimized on perturbed scenarios.
- Perturbed evaluator: 40 representative scenario/placement pairs matched the official judge exactly.

Raw measurements and configuration are preserved separately from summaries.

The current median `kittens` greedy construction time is 31.173 s. This is
consistent with the previously recorded optimized run of 34.263 s and remains
far below the historical pre-index-optimization time of 127.933 s. These older
figures are context only; all method comparisons below use the repeated current
measurements from the same protocol.

## 4. Local Search results

### Contribution over the identical greedy start

| Dataset | Greedy | Greedy + LS | Gain | Relative gain | Median construction | Median LS | Runtime overhead |
|---|---:|---:|---:|---:|---:|---:|---:|
| me_at_the_zoo | 507,906 | 507,906 | 0 | 0.0000% | 14.597 ms | 5.293 ms | 36.3% |
| videos_worth_spreading | 608,302 | 609,221 | 919 | 0.1511% | 304.976 ms | 588.792 ms | 193.1% |
| trending_today | 499,794 | 499,851 | 57 | 0.0114% | 2,574.571 ms | 371.357 ms | 14.4% |
| kittens | 1,021,681 | 1,021,735 | 54 | 0.0053% | 31,009.919 ms | 3,737.755 ms | 12.1% |

Local Search contributes most on `videos_worth_spreading`; on the largest instance its absolute improvement is only 54 points.

During the audit, the original repeated endpoint-connection scan made the
`trending_today` greedy-plus-search run take about 35.8 s end to end, and a
bounded `kittens` search was not practical. After the behavior-preserving
best/second-best latency index, the repeated median Local Search phase is
0.371 s on `trending_today` and 3.738 s on `kittens`. This is an implementation
speedup of the same neighborhood and selection policy, not an algorithmic score
improvement; all previously known small and official Local Search scores were
preserved.

### Neighborhood ablation

Insertion-only Local Search accepted no moves on any official greedy solution. Replacement-only produced:

- `videos_worth_spreading`: +915 of the complete method's +919;
- `trending_today`: +31 versus the complete method's +57;
- `kittens`: the same +54 score with 370 replacements versus 368 replacements and 15 insertions in the complete run.

Therefore replacements explain almost all direct improvement. Insertions can still matter after replacements change free capacity, especially on `trending_today`; the complete neighborhood is not simply the sum of isolated ablations.

### Search effort and convergence

| Dataset | Evaluated | Accepted | Acceptance rate | 50% gain by move/time | 90% gain by move/time | Stop reason |
|---|---:|---:|---:|---:|---:|---|
| me_at_the_zoo | 69 | 0 | 0% | — | — | no improving move |
| videos_worth_spreading | 335,653 | 117 | 0.0349% | 53 / 172 ms | 99 / 358 ms | three-pass limit |
| trending_today | 4,015 | 13 | 0.3238% | 8 / 317 ms | 13 / 331 ms | no improving move |
| kittens | 14,673,917 | 383 | 0.0026% | 186 / 2,466 ms | 333 / 3,190 ms | three-pass limit |

Most evaluated moves fail. Improvement is front-loaded in score per accepted move, but 90% of the gain still consumes most of the observed search time. `videos_worth_spreading` and `kittens` are not proven local optima because they stop at the pass limit.

### Dependence on initial quality

Baseline + Local Search ends at 451,979; 502,015; 50,103; and 99,730. Those are large improvements over each baseline, but all remain far below the greedy starts. Under this neighborhood and three-pass budget, Local Search refines a strong construction; it does not independently recover one.

## 5. Opportunity-Cost Greedy results

### Official scores and median algorithm time

| Dataset | Greedy | OCAG | OCAG difference | Greedy + LS | OCAG + LS | Median G / O / G+LS / O+LS |
|---|---:|---:|---:|---:|---:|---|
| me_at_the_zoo | 507,906 | 507,906 | 0 | 507,906 | 507,906 | 14.6 / 17.0 / 18.9 / 20.3 ms |
| videos_worth_spreading | 608,302 | 608,329 | +27 | 609,221 | 609,323 | 307.9 / 317.2 / 894.4 / 680.3 ms |
| trending_today | 499,794 | 499,794 | 0 | 499,851 | 499,851 | 2.555 / 2.642 / 2.935 / 3.065 s |
| kittens | 1,021,681 | 1,021,938 | +257 | 1,021,735 | 1,021,997 | 31.173 / 31.110 / 34.635 / 34.908 s |

OCAG changes construction on two datasets, ties on two, and never reduces nominal official score in this four-instance study. That observation is not a universal guarantee.

`trending_today` has zero positive static-regret placements, so OCAG exactly reproduces greedy. `me_at_the_zoo` has positive regrets but ends with the same placement. On `videos_worth_spreading`, OCAG selects 1,273 positive-regret placements; on `kittens`, 6,375.

Placement diagnostics support a real priority change rather than tie-breaking:

- `videos_worth_spreading` greedy/OCAG Jaccard similarity is 0.861, with 916 differing placement pairs.
- `kittens` similarity is 0.788, with 2,559 differing pairs.
- Cache utilization remains essentially 100%, while OCAG stores slightly more unique videos and slightly fewer duplicate copies on both.

This does not by itself prove that diversity causes the score gain; it only characterizes the changed placements.
The recorded OCAG trace shows which selected placements carried regret, but the
study does not align every lazy-queue pop with the original greedy trace.
Therefore it does not claim an exact first-divergence step or attribute a
particular final pair difference to one isolated decision.

### Equal-budget interpretation

- On `kittens`, OCAG is 203 points better than greedy + Local Search and slightly faster in median time. It dominates that repair strategy in this measured comparison.
- On `videos_worth_spreading`, greedy + Local Search is 892 points better than OCAG but takes about 2.8 times OCAG's median algorithm time. Repair is more valuable when the extra budget is available.
- On `trending_today`, OCAG adds no score; Local Search adds 57 points for about 0.38 s beyond greedy.
- On `me_at_the_zoo`, neither method improves the greedy score.

### Complementarity

Compared directly with greedy + Local Search, OCAG + Local Search is:

- tied on `me_at_the_zoo` and `trending_today`;
- +102 on `videos_worth_spreading`;
- +262 on `kittens`.

Thus OCAG does not make Local Search unnecessary. The best observed official scores use both methods on the two datasets where OCAG changes construction.

## 6. Robustness study

### Models

1. **Multiplicative noise:** each aggregated demand is independently multiplied by a nonnegative Gaussian factor with standard deviation 5%, 10%, 20% or 30%.
2. **Localized shift:** for each paired seed, 20% of endpoints and 10% of videos are selected; matching demand counts are multiplied by three. Other counts are unchanged, so total demand is not conserved.

These are synthetic prediction-error models, not observed traffic. Algorithms are compared within the same scenario; raw changes between scenarios are not interpreted as intrinsic degradation.

### Main paired findings

At 30% multiplicative noise:

- `kittens` OCAG beats greedy in 30/30 scenarios, with mean paired difference +259.2. OCAG+LS beats greedy+LS in 30/30, mean +264.2.
- `videos_worth_spreading` OCAG beats greedy in 17/30 but has mean difference -0.5. OCAG+LS beats greedy+LS in 23/30, mean +75.1.
- `me_at_the_zoo` and `trending_today` constructions remain identical, so paired differences are zero.

Under localized shifts:

- `kittens` OCAG beats greedy in 29/30, mean +244.4. OCAG+LS beats greedy+LS in 29/30, mean +248.5.
- `videos_worth_spreading` OCAG beats greedy in 17/30 but has mean difference -11.4. OCAG+LS beats greedy+LS in 20/30, mean +80.8.

The nominal OCAG-only gain on `videos_worth_spreading` is therefore fragile: it wins more scenarios than it loses, but its losses are larger on average at the strongest perturbations. Local Search after OCAG is more robust than OCAG alone there. The `kittens` improvement is much more stable.

There is no evidence in these experiments that Local Search trades nominal score for poorer robustness. Its fixed placement remains better than greedy on average under both strong perturbation models where it differs nominally.

## 7. Scientific discussion

### Supported hypotheses

- H1 is supported: Local Search primarily repairs one-for-one packing choices, and its impact depends strongly on the dataset and starting quality.
- H2 is partially supported: fixed alternative regret prevents a proven synthetic mistake and improves two official constructions, especially `kittens`, but has no effect on two datasets.
- H3 is supported on two datasets: OCAG provides a better start that Local Search preserves and further improves.
- H4 is supported for `videos_worth_spreading` OCAG alone, but not for `kittens`; robustness is instance-dependent.

### Small-instance optimality

Exhaustive enumeration proves:

- On `local-search.in`, greedy and OCAG score 370,588; the one-cache replacement reaches the optimum 529,411.
- On `opportunity-cost.in`, greedy and greedy+LS score 549,500; OCAG reaches the optimum 925,000. The Local Search neighborhood cannot coordinate the required two-cache reassignment.

These examples establish mechanisms, not typical-case frequency.

### Threats to validity

- Four official instances cannot establish general superiority.
- The OCAG formula was motivated before official evaluation and has no tuned coefficient, but algorithm selection still occurred within this project rather than on an external benchmark suite.
- Static regret ignores later changes in alternatives and correlated coverage.
- The Local Search pass cap limits conclusions about full local optima.
- Fresh JVM timing includes within-run JIT effects, though parsing and output are excluded and all methods use the same process model.
- Reliable peak memory was unavailable; only the common 4 GB heap limit is documented.
- Robustness scenarios are synthetic. They assess sensitivity to specified perturbations, not real production traffic.
- All robustness placements use nominal training demand as required. Structural stability under reoptimization was not studied.
- Final-placement diagnostics do not constitute a causal decomposition of each OCAG gain; a matched candidate-by-candidate queue trace would be required for that stronger claim.

## 8. Conclusions

There is no single winner across all datasets.

Local Search is most useful when the greedy solution contains profitable one-cache replacements, as on `videos_worth_spreading`. OCAG is most useful when good placements have uneven alternatives and capacity assignment matters, as on `kittens` and the exact opportunity-cost example. On `kittens`, anticipating opportunity cost is more effective than spending the same approximate budget on the existing repair method. On `videos_worth_spreading`, repair produces the larger nominal gain.

The methods are complementary: the best measured solutions on both affected official datasets use OCAG followed by Local Search. Robustness depends on the instance; the large `kittens` gain generalizes across the specified scenarios, whereas the small OCAG-only `videos_worth_spreading` gain does not reliably do so.

## 9. Future work

- Evaluate on independent non-official instances not used during method development.
- Compare the three-pass limit with explicit equal-time Local Search budgets.
- Investigate a dynamic regret update while preserving valid lazy bounds.
- Study a defensible cache shadow price derived from a fractional capacity relaxation.
- Measure structural stability when construction is rerun on perturbed training demand.
- Obtain reliable peak-memory profiling on an unrestricted host.

## Presentation-ready summary

### What is genuinely original?

The project does not only add another solver. It separates construction from repair, proves their different mechanisms on exact tiny cases, measures cost and convergence, and evaluates fixed placements under paired demand uncertainty. OCAG is a parameter-free constructive regret heuristic compatible with the existing lazy greedy architecture.

### Why is OCAG different?

Ordinary greedy asks, “What gives the most benefit now?” OCAG also asks, “How much worse is this video's best alternative cache?” Local Search instead starts after construction and changes at most one cache at a time.

### What did Local Search contribute?

It improved three official solutions, primarily through one-for-one replacements:
+919 on `videos_worth_spreading`, +57 on `trending_today`, and +54 on
`kittens`. It accepted no insertion-only moves from the greedy starts. The
largest gain cost about 0.59 s median beyond construction, while the `kittens`
gain cost about 3.74 s.

### Strongest result

On `kittens`, OCAG scores 1,021,938 in 31.110 s median, versus greedy+Local Search at 1,021,735 in 34.635 s. OCAG+Local Search reaches 1,021,997. OCAG's advantage persists in every 30%-noise scenario and 29/30 localized shifts.

### Unexpected result

The small nominal OCAG gain on `videos_worth_spreading` becomes slightly negative on average under the strongest perturbations, despite winning 17/30 scenarios. A nominal gain is not automatically robust.

### Proposed single-slide narrative

**Title:** Anticipate or repair greedy cache decisions?

1. Left: one two-cache opportunity-cost example showing greedy 549,500, Local Search 549,500, OCAG/optimum 925,000.
2. Center: official comparison table highlighting dataset dependence and `kittens` OCAG dominance.
3. Right: paired robustness statement—stable on `kittens`, fragile OCAG-only gain on `videos_worth_spreading`.
4. Bottom conclusion: construction regret and repair are complementary; neither universally dominates.

### Three-minute pitch

Explain the original marginal-gain greedy, show why a video with no good alternative is urgent, contrast OCAG construction with one-cache Local Search repair, then present the `kittens` result and the robustness caveat.

### Five-minute demonstration

1. Run the regression tests.
2. Run greedy, Local Search and OCAG on `opportunity-cost.in`.
3. Judge the three outputs and show that only OCAG reaches the exact optimum.
4. Open `opportunity-cost-summary.csv` and `robustness-summary.csv` for the official evidence.

### Likely technical questions

- Why does fixed regret preserve lazy queue correctness?
- Why add regret rather than multiply by it?
- Why can Local Search not fix the two-cache example?
- How are removal losses computed exactly when duplicate copies exist?
- Why are accepted-move rates so low?
- Why does OCAG do nothing on `trending_today`?
- Are the robustness perturbations volume-conserving?
- Why are no memory figures reported?
- Can these conclusions generalize beyond four instances?

### Limitations to admit

Static regret is an approximation, Local Search is capped at three passes, the official set is small, robustness traffic is synthetic, and memory could not be measured reliably. These limitations constrain the claims but do not invalidate the controlled comparisons.
