#!/usr/bin/env python3
"""Read-only archive validation and paired pre/post-merge solver checks."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
FROZEN = 'd30ef6e87cd900aaf904baa2b5c640903205392e'


def run(command, timeout=240):
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True,
                            text=True, timeout=timeout, check=True)
    return result.stdout.strip()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frozen-root', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, default=Path('tmp/promo-inputs'))
    parser.add_argument('--judge', type=Path, default=Path('tmp/judgeHashCode2017.exe'))
    parser.add_argument('--wide-judge', type=Path, default=Path('tmp/judgeHashCode2017-wide.exe'))
    parser.add_argument('--exact-judge', type=Path, default=Path('tmp/judge-exact.exe'))
    parser.add_argument('--report', type=Path, default=Path('tmp/integration-checks.json'))
    parser.add_argument('--quick', action='store_true', help='Use the existing independent judge evidence; no V2 benchmark runs')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('study', ROOT / 'ultimate-experiments.py')
    study = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(study)
    work = ROOT / 'tmp/integration-validation'
    work.mkdir(parents=True, exist_ok=True)
    report = dict(frozen_commit=FROZEN, unchanged_sources=[], archive_checks=[], benchmarks=[])
    for source in sorted((args.frozen_root / 'src/main/java').rglob('*.java')):
        relative = source.relative_to(args.frozen_root)
        if source.name in {'Main.java', 'LocalSearchSolver.java', 'ScientificExperimentRunner.java'}:
            continue
        assert source.read_bytes().replace(b'\r\n', b'\n') == (ROOT / relative).read_bytes().replace(b'\r\n', b'\n'), relative
        report['unchanged_sources'].append(relative.as_posix())
    print('All original sources except the three reviewed integration points are unchanged.', flush=True)
    test = ROOT / 'src/test/java/com/hashcode/streaming/solver/IntegrationReplayTest.java'
    outputs = []
    for classes in [args.frozen_root / 'out', ROOT / 'out']:
        source_root = args.frozen_root if classes == args.frozen_root / 'out' else ROOT
        sources = sorted((source_root / 'src/main/java').rglob('*.java'))
        compiled = work / source_root.name
        compiled.mkdir(exist_ok=True)
        run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', compiled, *sources, test])
        outputs.append(run(['java', '-cp', compiled,
                            'com.hashcode.streaming.solver.IntegrationReplayTest']))
    assert outputs[0] == outputs[1], outputs
    report['fixed_work_replay'] = outputs[0]
    print('Frozen/integrated exact placements and move traces match: ' + outputs[0], flush=True)
    integrity = json.loads((ROOT / 'experiments/ultimate-solver-v2/historical-integrity.json').read_text())
    reviewed = {'src/main/java/com/hashcode/streaming/solver/LocalSearchSolver.java',
                'experiments/SCIENTIFIC_REPORT.md'}
    for path, expected in integrity['files'].items():
        # These reviewed integrations are preserved exactly at the backup commit.
        checked_path = args.frozen_root / path if path in reviewed else ROOT / path
        assert digest(checked_path) == expected, path
    original_report = (args.frozen_root / 'experiments/SCIENTIFIC_REPORT.md').read_text()
    assert (ROOT / 'experiments/SCIENTIFIC_REPORT.md').read_text().endswith(original_report.split('\n', 2)[2])
    report['historical_files_preserved'] = len(integrity['files'])
    report['reviewed_historical_integrations'] = sorted(reviewed)
    inventory = study.read_csv(ROOT / 'experiments/promo-benchmark/instance-inventory.csv')
    for filename in ['all-33-fresh-results.csv', 'all-33-best-known-results.csv']:
        rows = study.read_csv(ROOT / 'experiments/ultimate-solver-v2' / filename)
        assert len(rows) == len(inventory) == 33
        for row, item in zip(rows, inventory):
            input_path = args.inputs / item['relative_path']
            output_path = ROOT / row['archived_solution']
            assert digest(input_path) == item['sha256'] == row['input_sha256']
            assert digest(output_path) == row['solution_sha256']
            raw, score = int(row['raw_objective']), int(row['score'])
            if not args.quick:
                problem = study.parse(input_path)
                assert study.checked(problem, input_path, output_path, study.judge_for(item, args)) == (raw, score)
                for judge in [args.wide_judge, args.exact_judge]:
                    assert study.old.judge(input_path, output_path, judge) == score
            report['archive_checks'].append(dict(table=filename, instance=row['instance'], score=score,
                                                judge_mode=row['judge_mode'], status='HASH_VERIFIED_PREVIOUSLY_JUDGED' if args.quick else 'PASS'))
        print('Verified ' + filename + ': 33/33.', flush=True)
    prior_checks = study.read_csv(ROOT / 'experiments/ultimate-solver-v2/independent-validation.csv')
    assert len(prior_checks) == 66 and all(row['validation'] == 'PASS' for row in prior_checks)
    report['mode'] = 'quick_existing_judge_evidence' if args.quick else 'full_paired_benchmarks'
    references = dict(me_at_the_zoo=507906, videos_worth_spreading=608302,
                      trending_today=499794, kittens=1021681)
    for dataset, expected in references.items():
        input_path = ROOT / 'examples' / (dataset + '.in')
        for algorithm, extra in [
                ('greedy', []),
                ('ultimate-v2-fixed-work', ['--policy', 'fixed', '--seconds', '180', '--max-units', '1000', '--seed', '20170317']),
                ('ultimate-v2-timed', ['--policy', 'intensive', '--seconds', '10', '--seed', '20170317'])]:
            if args.quick and algorithm != 'greedy':
                continue
            pair = []
            for version, classes in [('frozen', args.frozen_root / 'out'), ('integrated', ROOT / 'out')]:
                output = work / (dataset + '-' + algorithm + '-' + version + '.txt')
                command = ['java', '-Xmx4g', '-cp', classes, 'com.hashcode.streaming.Main',
                           'greedy' if algorithm == 'greedy' else 'ultimate-v2', input_path, output, *extra]
                start = time.perf_counter()
                stdout = run(command)
                elapsed = time.perf_counter() - start
                raw, score = study.checked(study.parse(input_path), input_path, output, args.judge)
                metadata = json.loads(stdout) if algorithm != 'greedy' else {}
                record = dict(dataset=dataset, algorithm=algorithm, version=version, seed=20170317,
                              options=extra, score=score, raw_objective=raw, wall_seconds=elapsed,
                              solution_sha256=digest(output), metadata=metadata)
                report['benchmarks'].append(record)
                pair.append(record)
                print(f'{dataset} {algorithm} {version}: {score} ({elapsed:.3f}s)', flush=True)
            if algorithm == 'greedy':
                assert all(row['score'] == expected for row in pair), pair
            if algorithm != 'ultimate-v2-timed':
                assert pair[0]['solution_sha256'] == pair[1]['solution_sha256'], pair
            else:
                # Wall-clock policies may stop on different moves. Exact fixed-work
                # equality and unchanged engine sources isolate this from code changes.
                pair[1]['timed_score_delta'] = pair[1]['score'] - pair[0]['score']
                pair[1]['comparison_type'] = 'wall_clock_observation_not_deterministic_regression'
    report['status'] = 'PASS'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('Integration validation PASS; ' + str(args.report), flush=True)


if __name__ == '__main__':
    main()
