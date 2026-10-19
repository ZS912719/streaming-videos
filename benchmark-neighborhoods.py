#!/usr/bin/env python3
"""Compare one- and two-removal Local Search with identical repair-time budgets."""

import argparse
import csv
from datetime import datetime
from decimal import Decimal, ROUND_CEILING
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


def second_bins(record, trace):
    """Attribute positive raw gains to [s, s+1); mark time after an early stop."""
    moves = trace[1:]
    if len(moves) != int(record['accepted_moves']):
        raise ValueError('Trace move count differs from metrics')
    if sum(int(p['saved_latency_gain']) for p in moves) != int(record['saved_latency_gain']):
        raise ValueError('Trace raw gain differs from metrics')
    gains, counts = {}, {}
    for point in moves:
        second = int(Decimal(point['elapsed_ms']) // Decimal(1000))
        gain = int(point['saved_latency_gain'])
        if gain <= 0:
            raise ValueError('Nonpositive accepted move')
        gains[second] = gains.get(second, 0) + gain
        counts[second] = counts.get(second, 0) + 1
    actual = Decimal(record['search_ms'])
    horizon = max(actual, Decimal(record['budget_ms']))
    length = max(int((horizon / 1000).to_integral_value(rounding=ROUND_CEILING)),
                 max(gains, default=-1) + 1)
    rows, cumulative = [], 0
    for second in range(length):
        gain = gains.get(second, 0)
        cumulative += gain
        active = max(Decimal(0), min(actual, Decimal((second + 1) * 1000)) - second * 1000)
        rows.append(dict(dataset=record['dataset'], budget_ms=record['budget_ms'],
            variant=record['variant'], repetition=record['repetition'],
            second=second + 1, interval_start_s=second, interval_end_s=second + 1,
            active_ms=float(active), raw_gain_request_ms=gain,
            accepted_moves=counts.get(second, 0), cumulative_raw_gain_request_ms=cumulative,
            after_search_stop=active == 0, termination=record['termination']))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--budgets-ms', nargs='+', type=int, default=[1000, 5000, 10000])
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--heap', default='4g')
    parser.add_argument('--datasets', nargs='+', choices=DATASETS, default=DATASETS)
    parser.add_argument('--audit', action='store_true',
                        help='Independently audit the complete final neighborhood outside repair time')
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
        independent_audit=args.audit,
        second_bins='[s,s+1); raw accepted request-ms; active_ms=0 marks post-stop padding',
        input_sha256={}, seeds={})
    rows = []
    seconds = []
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
                        if args.audit:
                            command.append('--audit')
                        subprocess.run(command,
                                       cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT,
                                       timeout=budget / 1000 + 120)
                    record, = csv_rows(metrics)
                    assert int(record['initial_score']) == int(seed_record['score'])
                    record.update(repetition=repetition,
                                  worker_ms=round((time.perf_counter() - start) * 1000, 3))
                    rows.append(record)
                    write_csv(output / 'runs.csv', rows)
                    seconds.extend(second_bins(record, csv_rows(trace)))
                    write_csv(output / 'per-second-runs.csv', seconds)
                    (output / f'{stem}.trace.csv').write_bytes(trace.read_bytes())
                    print(f'{dataset} / {budget} ms / {variant} / {repetition}: '
                          f'score={record["score"]}, search_ms={record["search_ms"]}, '
                          f'moves={record["accepted_moves"]}, '
                          f'double={record["double_replacements"]}, '
                          f'last_ms={record["last_improvement_ms"]}, '
                          f'verified={record["verified_local_optimum"]}, {record["termination"]}', flush=True)
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
                              'passes', 'worker_ms', 'last_improvement_ms', 'audit_ms']:
                    values = [float(r[field]) for r in group if r[field] != '']
                    for label, function in [('median', statistics.median), ('min', min), ('max', max)]:
                        result[field + '_' + label] = round(function(values), 3) if values else ''
                result['budget_stops'] = sum(r['termination'] == 'time_budget' for r in group)
                result['converged'] = sum(r['termination'] == 'no_improving_move' for r in group)
                result['audited_runs'] = sum(r['verified_local_optimum'] != '' for r in group)
                result['verified_local_optima'] = sum(r['verified_local_optimum'] == 'true' for r in group)
                remaining = [int(r['best_remaining_gain']) for r in group if r['best_remaining_gain'] != '']
                result['best_remaining_gain_max'] = max(remaining) if remaining else ''
                summary.append(result)
            diffs = [int(b['score']) - int(a['score'])
                     for a, b in zip(groups['one'], groups['two'])]
            paired.append(dict(dataset=dataset, budget_ms=budget,
                score_delta_median=statistics.median(diffs), score_delta_min=min(diffs),
                score_delta_max=max(diffs), two_wins=sum(d > 0 for d in diffs),
                ties=sum(d == 0 for d in diffs), two_losses=sum(d < 0 for d in diffs)))
    write_csv(output / 'summary.csv', summary)
    write_csv(output / 'paired.csv', paired)
    second_summary = []
    for dataset in args.datasets:
        for budget in args.budgets_ms:
            for variant in ['one', 'two']:
                group = [r for r in seconds if r['dataset'] == dataset + '.in'
                         and int(r['budget_ms']) == budget and r['variant'] == variant]
                for second in sorted({r['second'] for r in group}):
                    samples = [r for r in group if r['second'] == second]
                    result = dict(dataset=dataset, budget_ms=budget, variant=variant, second=second,
                                  samples=len(samples), active_runs=sum(r['active_ms'] > 0 for r in samples))
                    for field in ['raw_gain_request_ms', 'accepted_moves', 'cumulative_raw_gain_request_ms']:
                        values = [r[field] for r in samples]
                        for label, function in [('median', statistics.median), ('min', min), ('max', max)]:
                            result[field + '_' + label] = function(values)
                    second_summary.append(result)
    write_csv(output / 'per-second-summary.csv', second_summary)
    print('Benchmark complete: ' + str(output), flush=True)


if __name__ == '__main__':
    main()
