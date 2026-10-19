# Ultimate V2 integration verification

## Recovery point

The complete validated pre-merge work is commit
`d30ef6e87cd900aaf904baa2b5c640903205392e`, also preserved by the pushed tag
`backup/ultimate-v2-validated-pre-merge`. No shared history was rewritten.
The incoming default-branch revision was `9d66a35351b410d946c9247ccb492e6167da9359`.

## Resolution and compatibility

Only `Main.java` and `LocalSearchSolver.java` conflicted. The dispatcher keeps
the Ultimate V1/V2 routes and the two-argument Greedy shorthand, and uses the
shared registry for the original commands. Both dynamic constructors remain
registered. The authoritative Local Search keeps its three-pass default,
configurable pass/move limits, first/best selection and both traversal orders.
The teammate's optional two-removal neighborhood and deadline API are combined
with those controls, not substituted for the advanced engines. Original trace
and statistics constructor signatures remain supported.

The V1/V2 engines, their constructors, benefit index, placement state, packing,
repacking and exchange implementations are unchanged. All original Java sources
except the dispatcher, combined Local Search and scientific metrics adapter
match the backup after canonical line-ending normalization.

The registry, comparison tools, independent neighborhood audit, diagnostics,
historical reports and optional web workspace were retained. The 3/20-pass
comparison helper now builds explicit snapshots independently of the production
default. No solver dependency or duplicate engine was introduced. Maven was
used only to test the pre-existing optional web module.

## Checks passed

- Clean Java build and original regression suite.
- Advanced constructor, exchange, packing and knapsack differential tests.
- General solver and Ultimate V2 incremental/replay/checkpoint tests.
- Frozen versus integrated: 1,920 explicitly tuned single-cache searches,
  40 default Local Search placements and 80 fixed-work V1/V2 searches have
  identical placements and deterministic move traces. Fingerprint:
  `be4f3f732cd3316a1be9857f30d6401e43b36975b77097fc33f187cb541e86b2`.
- Python tests: 7 comparison/metrics, 7 promotion, 7 V1 tools and 4 V2 tools.
- Optional web module: 8 tests, zero failures/errors/skips.
- Clean frozen export independently rejudged all 66 final archived outputs,
  including the labelled overflow-safe/extended cases and normalized inputs.
- All 2,439 predecessor artifacts remain preserved. The merged Local Search and
  an added explanatory report preface are intentionally reviewed changes;
  their exact predecessors remain available at the backup commit. Historical
  numeric results and all 66 final solution byte hashes are unchanged.

Original Greedy was run before/after on every official instance; both score and
solution byte hash match. The retained best V2 archive remains:

| Dataset | Original Greedy, both versions | Retained best V2 |
| --- | ---: | ---: |
| me_at_the_zoo | 507906 | 512115 |
| videos_worth_spreading | 608302 | 612126 |
| trending_today | 499794 | 499966 |
| kittens | 1021681 | 1025188 |

New timed optimization benchmarks were cancelled on request. These V2 values
are the existing independently validated archive, not invented fresh timed
runs. The quick integration check reused that evidence and verified hashes;
the separate frozen-export validation reran the judges without solver search.
Single Greedy wall times in `validation-results.json` are observations, not a
performance comparison: other verification work was running concurrently.
Unchanged sources and fixed-work equality establish behavior preservation
without confusing time-budget variability with an algorithm change.

## Reproduction

Run from the repository root with Java 17+, Python and the original judge source:

```powershell
.\test.ps1
python -m unittest discover -s tests -v
python test-promotion-tools.py
python test-ultimate-tools.py
python test-ultimate-v2-tools.py
mvn -f web/pom.xml test
.\replay-frozen-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase validate
python verify-integration.py --frozen-root tmp/frozen-v2-d30ef6e-lf --quick
```

The frozen replay is isolated under ignored `tmp/`, keeps strict historical
source admission checks, and requires full Git history. Exact archived bytes
are protected by `.gitattributes`. New V2 runs use the integrated CLI directly;
no historical ledger or source fingerprint is relabelled as a new experiment.
