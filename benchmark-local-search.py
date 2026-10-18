#!/usr/bin/env python3
"""Compare baseline, greedy, and 3/20-pass Local Search in fresh sequential JVMs."""

import argparse
import csv
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parent
RUNNER = 'com.hashcode.streaming.experiments.ScientificExperimentRunner'
DEFAULT_DATASETS = ['me_at_the_zoo', 'videos_worth_spreading', 'trending_today', 'kittens']
VARIANTS = ['baseline', 'greedy', 'greedy-ls-3', 'greedy-ls-20']


def write_csv(path, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--heap', default='4g')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--datasets', nargs='+', default=DEFAULT_DATASETS,
                        choices=DEFAULT_DATASETS)
    args = parser.parse_args()
    if args.repetitions < 1 or args.timeout < 1:
        parser.error('Repetitions and timeout must be positive')
    if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]?', args.heap):
        parser.error('Invalid Java heap size')
    output = args.output_dir.resolve()
    if output.exists():
        parser.error('Output directory must not exist')
    java_home = Path(os.environ['JAVA_HOME'])
    java = str(java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    javac = str(java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac'))
    output.mkdir(parents=True)
    (ROOT / 'tmp').mkdir(exist_ok=True)
    raw = Path(tempfile.mkdtemp(prefix='ls20-benchmark-', dir=ROOT / 'tmp'))
    print(f'Raw artifacts: {raw}', flush=True)

    # Snapshot actual working-tree sources, including uncommitted work.
    source_root = ROOT / 'src/main/java'
    sources = sorted(source_root.rglob('*.java'))
    snapshot = raw / 'sources'
    hashes = {}
    for source in sources:
        relative = source.relative_to(source_root)
        target = snapshot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        hashes[str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
    local_relative = Path('com/hashcode/streaming/solver/LocalSearchSolver.java')
    local_text = (snapshot / local_relative).read_text(encoding='utf-8')
    if len(re.findall(r'MAX_PASSES = 20;', local_text)) != 1:
        raise RuntimeError('Expected production Local Search to have a 20-pass limit')
    classes = raw / 'classes-20'
    classes.mkdir()
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes)]
                   + [str(p) for p in sorted(snapshot.rglob('*.java'))], check=True, timeout=120)
    control_source = raw / 'control-3' / local_relative
    control_source.parent.mkdir(parents=True)
    control_source.write_text(local_text.replace('MAX_PASSES = 20;', 'MAX_PASSES = 3;'),
                              encoding='utf-8')
    control_classes = raw / 'classes-3'
    control_classes.mkdir()
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(classes),
                    '-d', str(control_classes), str(control_source)], check=True, timeout=120)
    tests = sorted((ROOT / 'src/test/java').rglob('*.java'))
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(classes),
                    '-d', str(classes)] + [str(p) for p in tests], check=True, timeout=120)
    subprocess.run([java, '-cp', str(classes), 'com.hashcode.streaming.SolverRegressionTest'],
                   cwd=ROOT, check=True, timeout=args.timeout)
    metadata = {
        'started_at_host': datetime.now().astimezone().isoformat(), 'platform': platform.platform(),
        'processor_identifier': os.environ.get('PROCESSOR_IDENTIFIER'),
        'java': subprocess.run([java, '-version'], capture_output=True, text=True,
                               check=True).stderr.strip(),
        'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                             text=True).strip(),
        'git_status': subprocess.check_output(['git', 'status', '--short'], cwd=ROOT,
                                               text=True),
        'source_sha256': hashes,
        'input_sha256': {name: hashlib.sha256((ROOT / f'examples/{name}.in').read_bytes())
                         .hexdigest() for name in args.datasets},
        'repetitions': args.repetitions, 'heap': args.heap, 'timeout_seconds': args.timeout,
        'raw_artifacts': str(raw), 'execution': 'sequential; fresh JVM; rotating variant order',
        'scoring': 'repository ScoreCalculator and SolutionValidator; no external judge',
        'timing': 'median/min/max; algorithm excludes parse, validation/scoring, and output',
        'control': 'same source snapshot, changing only MAX_PASSES from 20 to 3',
    }
    (output / 'configuration.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    rows = []
    pass_rows = []
    for dataset in args.datasets:
        for repetition in range(1, args.repetitions + 1):
            offset = (repetition - 1) % len(VARIANTS)
            order = VARIANTS[offset:] + VARIANTS[:offset]
            for variant in order:
                stem = f'{dataset}.{variant}.rep-{repetition}'
                metrics = raw / f'{stem}.metrics.csv'
                trace = raw / f'{stem}.trace.csv'
                classpath = (str(control_classes) + os.pathsep + str(classes)
                             if variant == 'greedy-ls-3' else str(classes))
                algorithm = 'greedy-ls' if variant.startswith('greedy-ls-') else variant
                start = time.perf_counter()
                with (raw / f'{stem}.log').open('w', encoding='utf-8') as log:
                    subprocess.run([java, '-Xmx' + args.heap, '-cp', classpath, RUNNER,
                                    algorithm, str(ROOT / f'examples/{dataset}.in'),
                                    str(raw / f'{stem}.out'), str(metrics), str(trace)],
                                   cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   check=True, timeout=args.timeout)
                worker_ms = (time.perf_counter() - start) * 1000
                with metrics.open(encoding='utf-8', newline='') as stream:
                    record, = list(csv.DictReader(stream))
                record.update(variant=variant, repetition=repetition,
                              worker_ms=round(worker_ms, 3))
                rows.append(record)
                write_csv(output / 'runs.csv', rows)
                print(f'{dataset} / {variant} / {repetition}: '
                      f'score={record["internal_final_score"]}, '
                      f'algorithm_ms={record["algorithm_ms"]}, '
                      f'passes={record["ls_passes"]}, stop={record["ls_termination"]}',
                      flush=True)
                if repetition == 1 and trace.exists():
                    trace_text = trace.read_text(encoding='utf-8')
                    (output / f'{dataset}.{variant}.trace.csv').write_text(trace_text,
                                                                          encoding='utf-8')
                    with trace.open(encoding='utf-8', newline='') as stream:
                        points = list(csv.DictReader(stream))
                    initial = points[0]
                    previous = initial
                    for pass_number in range(1, int(record['ls_passes']) + 1):
                        moves = [p for p in points if int(p['pass']) == pass_number]
                        last = moves[-1] if moves else previous
                        pass_rows.append(dict(dataset=dataset, variant=variant,
                            pass_number=pass_number, accepted_moves=len(moves),
                            saved_latency_gain=sum(int(p['saved_latency_gain']) for p in moves),
                            score=int(last['score_equivalent']),
                            cumulative_moves=int(last['accepted_move'])))
                        previous = last
    summary = []
    for dataset in args.datasets:
        for variant in VARIANTS:
            group = [r for r in rows if r['dataset'] == dataset + '.in'
                     and r['variant'] == variant]
            for field in ['internal_final_score', 'ls_passes', 'ls_accepted_moves',
                          'ls_termination', 'ls_saved_latency_gain']:
                if len({r[field] for r in group}) != 1:
                    raise RuntimeError(f'Non-deterministic {field}: {dataset}/{variant}')
            first = group[0]
            result = dict(dataset=dataset, variant=variant, repetitions=len(group),
                score=int(first['internal_final_score']), passes=int(first['ls_passes']),
                accepted_moves=int(first['ls_accepted_moves']), additions=int(first['ls_additions']),
                replacements=int(first['ls_replacements']), termination=first['ls_termination'])
            for field in ['algorithm_ms', 'construction_ms', 'local_search_ms', 'worker_ms']:
                values = [float(r[field]) for r in group]
                for label, function in [('median', statistics.median), ('min', min), ('max', max)]:
                    result[f'{field}_{label}'] = round(function(values), 3)
            summary.append(result)
    write_csv(output / 'summary.csv', summary)
    write_csv(output / 'passes.csv', pass_rows)
    # The larger budget must preserve the exact accepted-move prefix of the control.
    for dataset in args.datasets:
        traces = []
        for variant in ['greedy-ls-3', 'greedy-ls-20']:
            with (output / f'{dataset}.{variant}.trace.csv').open(
                    encoding='utf-8', newline='') as stream:
                traces.append([{key: value for key, value in row.items() if key != 'elapsed_ms'}
                               for row in csv.DictReader(stream) if int(row['pass']) <= 3])
        if traces[0] != traces[1]:
            raise RuntimeError(f'Accepted-move prefix differs for {dataset}')
    print(f'Benchmark complete: {output}', flush=True)


if __name__ == '__main__':
    main()
