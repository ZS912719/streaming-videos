#!/usr/bin/env python3
"""Compare one- and two-removal Local Search with identical repair-time budgets."""

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
RUNNER = 'com.hashcode.streaming.experiments.NeighborhoodBudgetRunner'
DATASETS = ['me_at_the_zoo', 'videos_worth_spreading', 'trending_today', 'kittens']


def csv_rows(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream))


def write_csv(path, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--budgets-ms', nargs='+', type=int, default=[1000, 5000, 10000])
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--heap', default='4g')
    parser.add_argument('--datasets', nargs='+', choices=DATASETS, default=DATASETS)
    args = parser.parse_args()
    if args.repetitions < 1 or any(b <= 0 for b in args.budgets_ms):
        parser.error('Budgets and repetitions must be positive')
    if len(set(args.budgets_ms)) != len(args.budgets_ms):
        parser.error('Budgets must be distinct')
    if len(set(args.datasets)) != len(args.datasets):
        parser.error('Datasets must be distinct')
    if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]?', args.heap):
        parser.error('Invalid heap size')
    output = args.output_dir.resolve()
    if output.exists():
        parser.error('Choose a new output directory')
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        parser.error('Set JAVA_HOME to a Java 17+ JDK')
    suffix = '.exe' if os.name == 'nt' else ''
    java = str(Path(java_home) / 'bin' / ('java' + suffix))
    javac = str(Path(java_home) / 'bin' / ('javac' + suffix))
    output.mkdir(parents=True)
    (ROOT / 'tmp').mkdir(exist_ok=True)
    raw = Path(tempfile.mkdtemp(prefix='neighborhood-budget-', dir=ROOT / 'tmp'))
    print('Raw artifacts: ' + str(raw), flush=True)
    source_root = ROOT / 'src/main/java'
    snapshot = raw / 'sources'
    hashes = {}
    for source in sorted(source_root.rglob('*.java')):
        relative = source.relative_to(source_root)
        target = snapshot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        hashes[str(relative)] = hashlib.sha256(target.read_bytes()).hexdigest()
    classes = raw / 'classes'
    classes.mkdir()
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes)]
                   + [str(p) for p in sorted(snapshot.rglob('*.java'))], check=True, timeout=120)
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(classes),
                    '-d', str(classes)]
                   + [str(p) for p in sorted((ROOT / 'src/test/java').rglob('*.java'))],
                   check=True, timeout=120)
    subprocess.run([java, '-cp', str(classes), 'com.hashcode.streaming.SolverRegressionTest'],
                   cwd=ROOT, check=True, timeout=120)
    config = dict(started_at_host=datetime.now().astimezone().isoformat(),
        platform=platform.platform(), processor=os.environ.get('PROCESSOR_IDENTIFIER'),
        java=subprocess.run([java, '-version'], capture_output=True, text=True,
                            check=True).stderr.strip(),
        git_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        source_sha256=hashes, raw_artifacts=str(raw), heap=args.heap,
        budgets_ms=args.budgets_ms, repetitions=args.repetitions,
        execution='sequential fresh JVMs; paired order alternates each repetition/budget',
        timing='repair wall time including index construction; no pass cap; cooperative deadline',
        scoring='repository capacity validator and official-formula scorer; no external judge',
        input_sha256={}, seeds={})
    rows = []
    for dataset in args.datasets:
        input_path = ROOT / f'examples/{dataset}.in'
        config['input_sha256'][dataset] = hashlib.sha256(input_path.read_bytes()).hexdigest()
        seed = raw / f'{dataset}.seed.out'
        seed_metrics = raw / f'{dataset}.seed.csv'
        with (raw / f'{dataset}.seed.log').open('w', encoding='utf-8') as log:
            subprocess.run([java, '-Xmx' + args.heap, '-cp', str(classes), RUNNER,
                            '--prepare', str(input_path), str(seed), str(seed_metrics)],
                           cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        seed_record, = csv_rows(seed_metrics)
        seed_record['sha256'] = hashlib.sha256(seed.read_bytes()).hexdigest()
        config['seeds'][dataset] = seed_record
        (output / 'configuration.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
        print(f'{dataset}: identical greedy seed score={seed_record["score"]}', flush=True)
        for budget_index, budget in enumerate(args.budgets_ms):
            for repetition in range(1, args.repetitions + 1):
                variants = ['one', 'two'] if (repetition + budget_index) % 2 else ['two', 'one']
                for variant in variants:
                    stem = f'{dataset}.{budget}ms.{variant}.rep-{repetition}'
                    metrics = raw / f'{stem}.metrics.csv'
                    trace = raw / f'{stem}.trace.csv'
                    start = time.perf_counter()
                    with (raw / f'{stem}.log').open('w', encoding='utf-8') as log:
                        command = [java, '-Xmx' + args.heap, '-cp', str(classes), RUNNER,
                                        variant, str(input_path), str(seed), str(raw / f'{stem}.out'),
                                        str(metrics), str(trace), str(budget)]
                        subprocess.run(command,
                                       cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT,
                                       timeout=budget / 1000 + 120)
                    record, = csv_rows(metrics)
                    assert int(record['initial_score']) == int(seed_record['score'])
                    record.update(repetition=repetition,
                                  worker_ms=round((time.perf_counter() - start) * 1000, 3))
                    rows.append(record)
                    write_csv(output / 'runs.csv', rows)
                    (output / f'{stem}.trace.csv').write_bytes(trace.read_bytes())
                    print(f'{dataset} / {budget} ms / {variant} / {repetition}: '
                          f'score={record["score"]}, search_ms={record["search_ms"]}, '
                          f'moves={record["accepted_moves"]}, '
                          f'double={record["double_replacements"]}, {record["termination"]}', flush=True)
    summary, paired = [], []
    for dataset in args.datasets:
        for budget in args.budgets_ms:
            groups = {}
            for variant in ['one', 'two']:
                group = [r for r in rows if r['dataset'] == dataset + '.in'
                         and int(r['budget_ms']) == budget and r['variant'] == variant]
                groups[variant] = group
                result = dict(dataset=dataset, budget_ms=budget, variant=variant,
                              repetitions=len(group), initial_score=group[0]['initial_score'])
                for field in ['score', 'search_ms', 'overrun_ms', 'accepted_moves', 'additions',
                              'single_replacements', 'double_replacements', 'saved_latency_gain',
                              'passes', 'worker_ms']:
                    values = [float(r[field]) for r in group if r[field] != '']
                    for label, function in [('median', statistics.median), ('min', min), ('max', max)]:
                        result[field + '_' + label] = round(function(values), 3) if values else ''
                result['budget_stops'] = sum(r['termination'] == 'time_budget' for r in group)
                result['converged'] = sum(r['termination'] == 'no_improving_move' for r in group)
                summary.append(result)
            diffs = [int(b['score']) - int(a['score'])
                     for a, b in zip(groups['one'], groups['two'])]
            paired.append(dict(dataset=dataset, budget_ms=budget,
                score_delta_median=statistics.median(diffs), score_delta_min=min(diffs),
                score_delta_max=max(diffs), two_wins=sum(d > 0 for d in diffs),
                ties=sum(d == 0 for d in diffs), two_losses=sum(d < 0 for d in diffs)))
    write_csv(output / 'summary.csv', summary)
    write_csv(output / 'paired.csv', paired)
    print('Benchmark complete: ' + str(output), flush=True)


if __name__ == '__main__':
    main()
