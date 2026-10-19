# Competitive instance study

## Selected input and result

`competitive_streaming_videos.in` is ready for submission. It contains 384 videos,
192 endpoints, 2012 request descriptions, 48 caches and 240 MB/cache. It is valid
under the supplied Google 2017 specification, including request-count and latency
limits, and safe for the unchanged supplied C++ judge. No additional promotion
instance-size, construction or resource rules were supplied, so compliance with
any such external restrictions remains unverified.

Generator: existing `generate-experiments.py`, new optional `competitive-v1`
family `mixed`, seed **17**, 16 groups. Input SHA-256:

```text
17f3c3960c1ff0142401a346921fd120350fbfbfd51d743a5d2a9788e00a7028
```

The archived output scores **980693** with the unchanged supplied official C++
judge. Its SHA-256 is:

```text
32d3250d00cf17e94007352a7f83bc2fb6f50f9def1b118274c74a75b777431f
```

The strongest tested control, including the multistart portfolio, scores
**977913**. Our selected pipeline's advantage is **2780 points (0.284279%)**.
This is a modest, real gap against these implementations, not proof of beating
other students or of general superiority.

## Structural mechanism

The generator extends the existing deterministic/domain-separated generator.
Each group contributes 24 videos and three caches. Endpoint types mix private
cache access, pairs, overlapping triads and bridges to neighboring groups.
There are 16 connected group structures, not independent copies of a planted
optimal solution. Bridge endpoints carry requests for both neighboring groups.
Sizes range from 22 to 115 MB, DC latencies from 1100 to 2800 ms and cache
latencies from 20 to 490 ms. Private demands make some alternatives scarce;
flexible demands compete for those caches. Limited space creates nontrivial
knapsack tradeoffs. Heterogeneous cache latencies make replicated placement
gains change as other copies are added.

Demand is generated probabilistically from explicit endpoint/video categories,
independently of video sizes. The generator uses no solver calls, placement file,
hidden answer or judge exploit. Selection among instances uses measured solver
outcomes afterwards. High absolute score was not the selection criterion.

On the selected instance, ordinary one-cache repair plateaus below the result
obtained by repacking. One indexed exchange then adds 634 points. Deeper
single-cache repacking and repeated bounded exchanges do not close the control
gap. This suggests construction-dependent basins and coordinated-placement
difficulty, not merely failure to run a three-pass LS long enough.

## Search protocol and selection bias

Discovery used four families (`scarcity`, `packing`, `overlap`, `mixed`) and
three fixed seeds (17, 29, 43), all with 16 groups. The first screen executed six
configurations per instance: our frozen promotion pipeline, matched static and
zero-regret advanced pipelines, legacy Greedy with ordinary and advanced LS,
and dynamic regret with ordinary LS. All 72 outputs and stage scores were judged.

The three largest relative gaps against matched advanced controls were
`mixed.seed_17.in`, `overlap.seed_43.in`, and `scarcity.seed_17.in`. Each received
two stronger reverse-cache-order, two-cycle references. The primary was selected
by its largest remaining relative gap, then frozen before validation seeds.
See `screening-results.csv`, `strong-results.csv`, `shortlist.json` and the
machine-readable generator manifest/configuration.

Validation used mixed-family seeds 101, 131 and 173. Their negative results were
not discarded and did not cause the primary seed to be reselected. Afterwards,
the already selected primary received the deeper-repair stress experiment below.
The stress experiment is explicitly additional evaluation, not part of the
original discovery screen or a fresh held-out instance.

## Algorithms and fair-compute checks

All algorithms reuse existing implementations. No annealing, tabu search,
evolutionary algorithm, unrelated framework or future adaptive solver was added.

The primary pipeline is exactly the promotion configuration: dynamic feasible-
alternative regret w=1; forward best-improvement insertion/replacement LS, at
most 10 passes and one move/cache/pass; repacking with K=64 and at most five
passes; best-improvement indexed exchange, 1000 seeded cache pairs, 64 stored
videos/cache, five rounds; final same LS. One cycle. Natural no-improvement
stops apply. The general deterministic work guards never trigger here.

Static and zero controls use the same canonical indexed constructor/tie rules,
changing only the regret rule/weight, followed by identical repair. This avoids
misattributing historical HashMap/lazy-ranking differences to dynamic regret.
Legacy Greedy is a separate reference, not the zero control by assumption.

Ordinary references use insertion/one-for-one replacement only. Legacy Greedy
ordinary LS gets 50 passes; dynamic ordinary gets the shared 10, and both stop
well before their cap on this primary input. Reverse references change cache
order and allow two full repair cycles.

Deep variants allow 50 LS passes, 20 repacking passes, **K=384** (all videos can
enter the candidate pool), 50 exchange rounds and up to three cycles. They stop
on a no-improvement cycle. These are existing neighborhoods with more work,
not a new algorithm. The multistart control portfolio independently constructs
and improves eight starts: static, zero and legacy-Greedy shared repair; static
and zero reverse repair; static, zero and legacy-Greedy deep repair. It returns
the highest validated exact objective among those starts. It excludes dynamic
regret, to make it a genuine competing construction portfolio.

All primary configurations were repeated in three fresh JVMs. Scores and hashes
were deterministic. The table reports median algorithm time, excluding JVM
startup, parsing, checkpoint output, external judge and compilation. Worker wall
medians are also retained in the CSV. The portfolio time is the median of the
three corresponding sums of its eight measured component runtimes, not a claim
of one-JVM portfolio speed. Exact parameter dictionaries are in the JSON.

| Algorithm | C++ judge score | Median algorithm ms |
| --- | ---: | ---: |
| Our dynamic + advanced LS | 980693 | 81.386 |
| Static + identical advanced LS | 977913 | 71.109 |
| Zero-regret + identical advanced LS | 977770 | 89.000 |
| Greedy + ordinary LS | 970952 | 43.948 |
| Greedy + identical advanced LS | 977770 | 90.503 |
| Dynamic + ordinary LS | 971984 | 42.283 |
| Static + reverse/two cycles | 977913 | 99.290 |
| Zero + reverse/two cycles | 977770 | 105.584 |
| Dynamic + deep repair | 980693 | 104.091 |
| Static + deep repair | 977913 | 93.608 |
| Zero + deep repair | 977770 | 115.132 |
| Greedy + deep repair | 977770 | 111.737 |
| Eight-start control portfolio | 977913 | 777.094 |

All workers have the same 4 GB heap and 180-second external safety limit; none
times out. Our worker wall median is 0.317 seconds versus 0.312 for matched
static and 0.326 for matched zero. Treat these tiny timing differences as noise,
not meaningful speed rankings. Deeper controls and the portfolio receive **more**
work than our selected run and still do not close its score gap. Thus our quality
gap is not purchased by substantially more runtime. This is not an exhaustive
equal-time parameter optimization of every possible competing method.

## Controlled contribution of regret and repair

Every score in this attribution table was directly checked by the C++ judge:

| Stage | Dynamic w=1 | Matched static w=1 |
| --- | ---: | ---: |
| Construction | 965573 | 965714 |
| Ordinary LS | 971984 | 967940 |
| Repacking | 980059 | 977913 |
| Exchanges | 980693 | 977913 |
| Final LS | 980693 | 977913 |

Dynamic construction alone is **141 points worse** than static construction.
Its better final basin appears after identical repair: final +2780 over static
and +2923 over zero. This supports a contribution of dynamic construction to
the repaired trajectory on this selected input, not an isolated claim that its
initial placements always score higher.

For the dynamic start, ordinary LS adds 6411, repacking adds 8075, exchanges add
634, and final LS adds zero. Advanced repair adds **8709** beyond ordinary LS.
The dynamic trace has 18 ordinary moves, 10 accepted repacks and one exchange.
Its first LS and repacking finish after three passes each; the exchange scan
finishes after two rounds. Deep settings do not improve the final score.
`competitive-stage-results.csv` preserves stage attribution.

Shared Greedy and zero-regret advanced scores and placements coincide on this
input. They are not independent evidence from two fundamentally different
optimizers. Strong references here are largely from one codebase and share
repair logic. No actual student executable, annealing, tabu or LNS implementation
was available; the measured portfolio is not a proxy guarantee against all of them.

## Robustness and alternatives: negative evidence matters

| Unseen mixed seed | Our pipeline | Strongest tested reference | Gap |
| --- | ---: | ---: | ---: |
| 101 | 921759 | 922508 (static) | -749 |
| 131 | 1005491 | 1006971 (zero) | -1480 |
| 173 | 963590 | 963671 (static reverse) | -81 |

The family does **not** show a consistent held-out advantage: the strongest
reference wins all three validation cases. The selected primary is an intentionally
selected competitive challenge, not evidence of generalization. Keeping it as a
submission candidate is defensible on its actual measured gap, but an opponent
with a different basin-escaping method may close that gap or beat us.

Secondary candidates remain reproducible under `candidates/`:

- `scarcity.seed_17.in`: 966446 versus strongest strengthened control 965640;
  an 806-point advantage. The scarcity family's initial matched screen favored
  dynamic in all three discovery seeds, but this was not a held-out strong-reference
  validation of that family.
- `overlap.seed_43.in`: 948405 versus strengthened zero/reverse 948254, only
  151 points. Strengthening reduced its apparent advantage substantially.
- Negative discovery outcomes include packing seed 43 (dynamic 779968 versus
  static 782819) and mixed seed 43 (1035768 versus static 1038245).

These results motivate retaining multiple constructors in a future general
solver rather than claiming universal superiority for w=1.

## Limits and reproducibility

No global optimum is known. K=384 deep repacking checks the full single-cache
knapsack best response when it reaches a no-change pass, but exchange checks
only the seeded 1000 of 1128 cache pairs. Neither certifies all compound
multi-cache neighborhoods or the absence of a better basin reachable by
worsening intermediate moves. No new global-local optimum claim is made.

The existing regression suite and new generator/ZIP tests pass. All experiment
outputs are capacity/duplicate checked, fully rescored in exact integer
arithmetic and judged stage by stage. Winning scores are archived with hashes.
Raw results are in discovery, strong, validation and three-repetition stress CSVs.

From the repository root:

```powershell
# Reproduce all stages (existing validated runs are resumed).
.\run-competitive-study.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
# Recompute the 12 final algorithm configurations in fresh JVMs and compare hashes.
python .\replay-promotion-study.py competitive
# Independently regenerate the selected input in an empty temporary directory.
python .\generate-experiments.py --competitive-family mixed --competitive-groups 16 --seeds 17 --output-dir tmp/competitive-regenerated
# Direct official check of the submitted pair.
.\tmp\judgeHashCode2017.exe experiments/competitive-instance/competitive_streaming_videos.in experiments/competitive-instance/competitive_streaming_videos.out
```

The generator refuses nonempty output directories. Use a new directory for a
repeat. All tools use portable repository-relative paths or explicit parameters.
Original solver defaults and all previous official records remain unchanged.
The final fresh replay recomputed all 12 primary algorithm configurations and
matched every C++ judge score and exact output hash; separate replay timings
are retained in `fresh-replay-results.csv`.
