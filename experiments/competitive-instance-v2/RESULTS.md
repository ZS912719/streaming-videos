# Competitive instance V2: bounded negative result

**Recommendation: submit V1.** Best V2 does not beat V1's verified relative gap.
No existing solver, default, historical result or V1 submission file was changed.
The promotion and original official-instance studies were not repeated.

| Metric | V1 | Best V2 |
| --- | ---: | ---: |
| Our official score | 980693 | 1014358 |
| Strongest reference score | 977913 | 1012043 |
| Absolute advantage | 2780 | 2315 |
| Relative advantage | 0.284279% | 0.228745% |
| Our median algorithm ms | 81.386 | 69.232 |
| Matched static median algorithm ms | 71.109 | 81.546 |

V1 timings are preserved historical measurements; V2 timings use three fresh
JVMs. Tiny timing differences are not evidence of a meaningful speed advantage.
Algorithm times exclude parsing, startup, checkpoint validation/output and judge.

## Diagnostic mechanism and search budget

V1 dynamic construction is 141 points worse than static, yet its repaired
solution wins. Final differences involve 35 videos: four groups contribute
positive net objective differences, five negative and seven zero. Positive
groups contribute 41961334 raw request-ms, offset by -27869972 elsewhere.
Examples include video 202 in cache 25 versus absent, video 238 in cache 27
versus absent, and video 125 replicated in caches 12/15/17 versus only 15.
These placement differences are not claims of standalone feasible improving moves.

Constructors first diverge at placement 20. After video 327 is already in cache
39, its current gain in cache 41 is 20408718, versus a best feasible alternative
of 9105270. Its current regret is 11303448, although its initial regret there
was zero. This is an **overlap-driven marginal-regret change**, not a capacity
exclusion at this step. Dynamic selects (41,327); static selects (2,15).
Existing full single-cache repacking and bounded exchanges do not make the
static/zero starts reach the same final placement. This supports a basin effect,
not a proof that all possible compound moves have been exhausted.

The existing generator and runners were reused. No solver infrastructure was
rebuilt, modified or expanded. Screening comprised **16 candidates, four families,
48 construction-only runs**:

- Focus: retain V1 group demands 2/5/8/9; multiply other video-group demands
  by 0.10/0.25/0.50/0.75. This deliberately feedback-directed transformation is
  selection-biased, not a general-purpose distribution or a planted answer.
- Pressure: capacities 180/210/270/300 on the V1 topology and requests.
- Coupling: add 1/2/3 neighboring-cache links and foreign-video demand on shared
  endpoints; an additional seed-29 degree-2 variant changes the underlying sample.
- Scarcity: reuse the existing scarce-opportunity family, seeds 17/29/43/71.

Full repair went to two demand-focus candidates, the tightest-capacity candidate,
and degree-3 coupling. The latter deliberately retained a weaker initial dynamic
placement to test whether repair changed the ranking. Only the best two parent
finalists received deeper/order/portfolio tests and two local perturbations each.

| Primary full candidate | Our score | Best matched reference | Relative gap |
| --- | ---: | ---: | ---: |
| focus-0.1 | 1013541 | 1011798 | +0.172268% |
| focus-0.25 | 987618 | 986126 | +0.151299% |
| pressure-180 | 815852 | 818316 | -0.301106% |
| coupling-3 | 946515 | 950427 | -0.411604% |

The most favorable construction gap, focus-0.1, was about 2.09%, but static
repair reduced it to 0.172%. Amplifying favorable V1 groups did **not** amplify
the final strong-reference gap. Extra overlap and tighter packing could hurt us.

## Strong references and counterattack

The matched pipeline is unchanged: dynamic w=1 / static w=1 / canonical zero
regret / legacy Greedy, followed by forward best-improvement LS (10 passes,
one move/cache/pass), K=64 repacking (five passes), indexed exchanges (five
rounds, 1000 pairs, 64 videos/cache, seed 20170317), final same LS, one cycle.
Natural no-improvement stopping and the existing work guards apply.

Counterattacks add reverse-cache-order/two-cycle static and zero starts, plus
static/zero/Greedy with LS 50, repacking 20 with K=384 (all videos), exchange
50, and up to three cycles. The eight-start portfolio retains the best of all
eight non-dynamic reference starts. Its score corresponds to an actually judged
archived output; its time is the sum of measured component times.

Best V2 is `focus-0.1-noise131`: existing mixed generator seed 17, 16 groups,
background multiplier 0.1, then independently seeded +/-2% request-count noise
with seed 131. Dimensions remain 384/192/2012/48/240.

| Best-V2 method | Original C++ judge score |
| --- | ---: |
| Our frozen pipeline | 1014358 |
| Static, identical repair | 1012043 |
| Zero or Greedy, identical repair | 1001573 |
| Static reverse or deeper repair | 1012043 |
| Zero/Greedy deeper repair | 1001573 |
| Eight-start reference portfolio | 1012043 |

Our three-run median is 69.232 ms; static's is 81.546 ms. The deeper static run
takes 114.026 ms and the portfolio 685.051 ms of aggregate component work.
Increased reference effort does not erase the gap. It nevertheless remains
smaller than V1. The controlled final gain over static is 2315; the larger
12785 gain over zero is **not** the primary competitive metric.

Our winning stages are 1002097 construction, 1004383 after ordinary LS, and
1014358 after repacking. Exchanges/final LS add zero. Repacking contributes
9975 beyond ordinary LS, so this V2 primarily exercises construction/packing
interaction, not a stronger observed exchange mechanism than V1.

## Local robustness, limits and stopping

| Nearby variant | Our score | Matched reference | Relative gap |
| --- | ---: | ---: | ---: |
| focus-0.1, noise 101 | 1013045 | 1011176 | +0.184834% |
| focus-0.1, noise 131 | 1014358 | 1012043 | +0.228745% |
| focus-0.25, noise 101 | 986098 | 985714 | +0.038957% |
| focus-0.25, noise 131 | 986800 | 986508 | +0.029599% |

These are local demand perturbations on the same topology, not independent
network samples or proof of generalization. Seed 131 was selected after looking
at these results, then counterattacked separately; it is not held-out evidence.
All observed gaps are below V1, so further similar evaluations were stopped.
The search establishes no global optimum, no full compound-neighborhood local
optimum, and no advantage over unavailable student/metaheuristic implementations.
Additional promotion submission restrictions remain unknown.

## Validation, artifacts and reproduction

Every candidate passes official input ranges and the original parser. All
reported experimental scores were checked by the unchanged supplied C++ judge;
request totals/products are safe. Existing workers validate capacities,
duplicates, exact full raw objectives and internal/judge consistency.
There are 107 archived runs / 403 judged stage outputs, including 48 cheap
screening constructors and 59 pipeline runs (including repetitions). Their total
measured algorithm time is 5.947 seconds. Three diagnostic constructors and a
nine-configuration fresh replay are additional small checks. Fresh replay
matched all nine official scores and exact output hashes; regeneration matched
the winning input hash. No artificial timeouts or crashes were used.

Best V2 files are `best-v2.in` / `best-v2.out` (preserved as a secondary candidate).
Exact settings and hashes are in `selected-configuration.json`. Raw CSVs retain
the screen, full comparison, counterattacks, perturbations, repetitions and replay.

```text
input  577a76ca72ec4e6c83d87456b6a3542c5522e7b020140c4954ab4ece32bb77e7
output 706ee76cdaa0804e71ffb3ce166382a39f1c85e7ec70d16b61f6521f5301192d
```

From the repository root (pass `-JudgeSource "<path>"` only if binaries are missing):

```powershell
.\experiments\competitive-instance-v2\reproduce.ps1
.\experiments\competitive-instance-v2\reproduce.ps1 -Fresh
.\tmp\judgeHashCode2017.exe experiments/competitive-instance-v2/best-v2.in experiments/competitive-instance-v2/best-v2.out
```

**Submit the unchanged V1** at
`experiments/competitive-instance/competitive_streaming_videos.in`.
