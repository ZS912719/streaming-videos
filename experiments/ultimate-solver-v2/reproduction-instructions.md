# Reproducing V2

Run commands from the repository root. Requirements: Java 17+, Python 3.9+,
g++ with C++17. No external solver libraries are needed. The promotion ZIP
and original supplied judge are external inputs. Supply their locations rather
than editing source paths.

```powershell
.\build.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>"
.\test.ps1
python .\test-promotion-tools.py
python .\test-ultimate-tools.py
python .\test-ultimate-v2-tools.py

java -Xmx4g -cp out com.hashcode.streaming.Main ultimate-v2 examples\me_at_the_zoo.in tmp\v2-demo.txt --seconds 10
.\tmp\judgeHashCode2017.exe examples\me_at_the_zoo.in tmp\v2-demo.txt

java -Xmx4g -cp out com.hashcode.streaming.Main ultimate-v2 examples\kittens.in tmp\v2-competition.txt --preset competition --seconds 180 --trace tmp\v2-trace.csv --metadata tmp\v2-metadata.json --checkpoints tmp\v2-checkpoints

.\run-ultimate-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Archive "<promotion-zip>"
# Resume: repeat the same command. Completed outputs are rehashed and rejudged.
# Inputs already extracted and checked:
.\run-ultimate-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase fresh
.\run-ultimate-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase incumbent
.\run-ultimate-v2.ps1 -JudgeSource "<path-to-judgeHashCode2017.cpp>" -Phase validate
Get-Content experiments\ultimate-solver-v2\scores-only.txt

java -Xmx4g -cp out com.hashcode.streaming.Main ultimate-v2 input.in tmp\improved.txt --seconds 180 --incumbent saved-valid-solution.txt
```

The frozen `solver-configuration.json` is authoritative for the evaluated policy
and optional flags. The V2 default is intensive; the historical `ultimate`,
`portfolio`, `competition` and two-argument greedy commands are unchanged.
`--policy fixed|adaptive|intensive` compares scheduling at
the same `--seconds` cap. Presets are quick=10, standard=60, competition=180,
deep=300 seconds. Presets select budgets, not dataset-specific settings.

Fresh evaluation never loads archived placements. Incumbent evaluation admits
the strongest validated previous/fresh/pilot output with the exact input hash.
Completed continuation results do not recursively become a new continuation on
resume. Every run is keyed by input, configuration, source hash and incumbent
hash. Earlier records and outputs are preserved.

Archive admission intentionally checks exact bytes. Preserve artifact line
endings when copying this study: automatic Git line-ending conversion can
invalidate recorded hashes even if the judged score is unchanged. The Java
source freeze also accepts canonical-LF identity; input and saved-solution
admission remain byte-exact. Do not bypass a failed archive hash check.

The two final CSVs distinguish fresh construction from archive improvement.
`scores-only.txt` exports the archive column in the original 33-input inventory
order. Do not compare that column to an unfiltered or differently sorted list.
Extended originals and conforming originals requiring wider judge arithmetic
have explicit labels. Exact aggregation/splitting produces compliant derived
inputs for validation without changing any demand or objective.

Solver timing excludes external judging. Parsing, construction, search,
validation, output and worker wall time are separate measurements. Time limits
are cooperative. Full validation/output can add overhead after the search cap.
Checkpoints are conservative: a move completing after a checkpoint cannot be
credited before it. Early-finished searches may publish their final checkpoint
without waiting for the rest of the budget. A checkpoint of a 180-second policy
is not silently relabelled as a separate short-budget policy experiment.

For policy comparisons or new experiments, keep the frozen final CSVs separate:

```powershell
python .\ultimate-v2-experiments.py pilot --names "medium_mixed_lambda.in" --budgets 30 --policies fixed,adaptive,intensive --label new-comparison
python .\ultimate-v2-experiments.py heldout
python .\ultimate-v2-experiments.py diagnostics
python .\ultimate-v2-experiments.py compare_v1
python .\ultimate-v2-experiments.py summary
```

The first command changes only the experimental input selection, not solver
behavior. Timed runs may follow different final trajectories because of JVM/OS
variation. Fixed-work differential tests establish exactness separately.
`source-versions/<source-hash>/` preserves the final source overlay. The
`build_version --version <source-hash>` action compiles that overlay into an
ignored replay directory without changing the working source or Git branch.
