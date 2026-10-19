#!/usr/bin/env python3
"""Exact evaluation and resumable experiments for the general-purpose solver (stdlib)."""
import argparse
import csv
import hashlib
import importlib.util
import json
import math
import os
import platform
import shutil
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
AREA = Path('experiments/ultimate-solver')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


old = load('historical_promotion', ROOT / 'promo-benchmark.py')
inventory = load('promotion_inventory', ROOT / 'inventory-promotion.py')
read_csv, write_csv, digest = old.read_csv, old.write_csv, old.digest


def canonical_input_digest(path):
    """Only portable CRLF/LF equivalence; never silently normalize numeric content."""
    return hashlib.sha256(Path(path).read_bytes().replace(b'\r\n',b'\n')).hexdigest()


def parse(path):
    """Independent line-strict parser; Python integers are unbounded."""
    with Path(path).open(encoding='ascii') as stream:
        def line(n):
            tokens = stream.readline().split()
            if len(tokens) != n or any(not t.isascii() or not t.isdecimal() for t in tokens):
                raise ValueError('Bad input line in ' + str(path))
            return list(map(int, tokens))
        v, e, r, c, x = line(5)
        if not (1 <= v <= 10000 and 1 <= e <= 1000 and 1 <= r <= 1000000
                and 1 <= c <= 1000 and 1 <= x <= 500000):
            raise ValueError('Unsupported dimensions')
        sizes = line(v)
        if not all(1 <= s <= 1000 for s in sizes):
            raise ValueError('Unsupported video sizes')
        endpoints = []
        for _ in range(e):
            dc, degree = line(2)
            if not (2 <= dc <= 4000 and 0 <= degree <= c):
                raise ValueError('Bad endpoint')
            links = {}
            for _ in range(degree):
                cache, latency = line(2)
                if not (0 <= cache < c and 1 <= latency <= min(500, dc-1)) or cache in links:
                    raise ValueError('Bad cache connection')
                links[cache] = latency
            endpoints.append((dc, links))
        demands = defaultdict(int)
        total = maximum = oversized = split_rows = 0
        for _ in range(r):
            video, endpoint, count = line(3)
            if not (0 <= video < v and 0 <= endpoint < e and 1 <= count <= 2**63-1):
                raise ValueError('Invalid demand')
            demands[video, endpoint] += count
            total += count
            maximum = max(maximum, count)
            oversized += count > 10000
            split_rows += (count + 9999)//10000
        if any(t.strip() for t in stream):
            raise ValueError('Trailing input')
    return dict(v=v, e=e, r=r, c=c, x=x, sizes=sizes, endpoints=endpoints,
                demands=dict(demands), total=total, maximum=maximum, oversized=oversized,
                naive_split_rows=split_rows,
                minimal_split_rows=sum((n+9999)//10000 for n in demands.values()),
                raw_upper_bound=sum(n*endpoints[e][0] for (v,e),n in demands.items()))


def evaluate(problem, path):
    """Strict output validity plus exact raw objective and floored score."""
    with Path(path).open(encoding='ascii') as stream:
        first = stream.readline().split()
        if len(first) != 1 or not first[0].isdigit():
            raise ValueError('Invalid solution header')
        used = int(first[0])
        if not 0 <= used <= problem['c']:
            raise ValueError('Invalid used cache count')
        caches = {}
        for _ in range(used):
            tokens = stream.readline().split()
            if not tokens or any(not t.isdigit() for t in tokens):
                raise ValueError('Invalid solution line')
            c, *videos = map(int, tokens)
            if c in caches or not 0 <= c < problem['c'] or len(set(videos)) != len(videos):
                raise ValueError('Duplicate/invalid cache or video')
            if any(v >= problem['v'] for v in videos):
                raise ValueError('Invalid video ID')
            if sum(problem['sizes'][v] for v in videos) > problem['x']:
                raise ValueError('Capacity exceeded')
            caches[c] = set(videos)
        if any(t.strip() for t in stream):
            raise ValueError('Trailing output')
    copies=[set() for _ in range(problem['v'])]
    for c,videos in caches.items():
        for v in videos:copies[v].add(c)
    raw = 0
    for (v,e), count in problem['demands'].items():
        dc, links = problem['endpoints'][e]
        stored=copies[v]
        # Evaluate the smaller exact intersection, independently of all Java incremental indexes.
        if len(stored)<len(links):
            best=min((links.get(c,dc) for c in stored),default=dc)
        else:
            best=min((latency for c,latency in links.items() if c in stored),default=dc)
        best=min(dc,best)
        raw += count * (dc-best)
    return raw, raw*1000//problem['total']


def exact_normalize(problem, path):
    """Aggregate duplicate (video,endpoint) demand, then split into <=10000 chunks."""
    rows = problem['minimal_split_rows']
    if rows > 1000000:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='ascii', newline='\n') as stream:
        stream.write(f"{problem['v']} {problem['e']} {rows} {problem['c']} {problem['x']}\n")
        stream.write(' '.join(map(str, problem['sizes']))+'\n')
        for dc, links in problem['endpoints']:
            stream.write(f'{dc} {len(links)}\n')
            for c, latency in links.items():
                stream.write(f'{c} {latency}\n')
        for (v,e), count in sorted(problem['demands'].items()):
            while count:
                part = min(10000, count)
                stream.write(f'{v} {e} {part}\n')
                count -= part
    normalized = parse(path)
    if normalized['demands'] != problem['demands'] or normalized['total'] != problem['total']:
        raise AssertionError('Demand changed in exact normalization')
    _, errors = inventory.inspect_input(path)
    if errors:
        raise AssertionError(errors)
    return True


def judge_for(item, args):
    return args.wide_judge if item['judge_integer_safe'] == 'False' else args.judge


def checked(problem, input_path, solution, executable):
    raw, score = evaluate(problem, solution)
    cpp = old.judge(input_path, solution, executable)
    if cpp != score:
        raise AssertionError(f'Independent/C++ mismatch: {input_path.name}: {score}/{cpp}')
    return raw, score


def coverage(args):
    """Complete all 33 BEFORE running any new optimization experiments."""
    rows, audits = [], []
    previous = {r['instance']:r for r in read_csv(Path('experiments/promo-benchmark/promo-details.csv'))}
    for item in read_csv(Path('experiments/promo-benchmark/instance-inventory.csv')):
        path = args.inputs / item['relative_path']
        if digest(path) != item['sha256']:
            raise ValueError('Input inventory hash mismatch')
        problem = parse(path)
        features, issues = inventory.inspect_input(path)
        if issues and issues != ['Request count outside [1,10000]']:
            raise ValueError('New unsupported input violation: ' + repr(issues))
        target = AREA/'coverage-solutions'/f"{int(item['order']):02d}-{path.stem}.txt"
        executable = judge_for(item, args)
        if item['filename'] in previous:
            result = previous[item['filename']]
            source = Path(result['solution'])
        else:
            config = dict(constructor='dynamic', weight=1, ls_passes=0, repack_passes=0,
                          candidates=64, exchange_rounds=0, cycles=1, cache_order='forward')
            result = old.experiment(path, 'coverage-dynamic-construction', config, executable,
                                    timeout=120, area=AREA/'coverage-runs')
            source = Path(result['solution'])
        raw, score = checked(problem, path, source, executable)
        if old.judge(path, source, args.wide_judge) != score:
            raise AssertionError('Wide and independent judge disagree')
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and evaluate(problem, target)[0] > raw:
            raise AssertionError('Refusing coverage archive regression')
        shutil.copyfile(source, target)
        normalized, normalized_hash, normalized_score = '', '', ''
        if issues:
            derived = Path('tmp/ultimate-normalized')/path.name
            if exact_normalize(problem, derived):
                normalized, normalized_hash = derived.as_posix(), digest(derived)
                # Wide arithmetic still required if aggregate requests exceed signed int.
                _, normalized_score = checked(parse(derived), derived, target, executable)
                if normalized_score != score:
                    raise AssertionError('Normalization changed score')
        mode = 'EXTENDED_WIDE_JUDGE' if issues else (
            'OFFICIAL_FORMAT_WIDE_JUDGE' if item['judge_integer_safe'] == 'False' else 'ORIGINAL_OFFICIAL')
        rows.append(dict(order=int(item['order']), instance=item['filename'], input_sha256=item['sha256'],
                         score=score, raw_objective=raw, algorithm_ms=result['algorithm_ms'],
                         solution=target.as_posix(), solution_sha256=digest(target), judge_mode=mode,
                         status='VALID_EXTENDED' if issues else 'VALID_OFFICIAL_FORMAT'))
        audits.append(dict(order=int(item['order']), instance=item['filename'], issues='; '.join(issues),
                           request_rows=problem['r'], oversized_rows=problem['oversized'],
                           max_request_count=problem['maximum'], total_requests=problem['total'],
                           naive_split_rows=problem['naive_split_rows'], minimal_split_rows=problem['minimal_split_rows'],
                           exact_normalized_path=normalized, normalized_sha256=normalized_hash,
                           normalized_score=normalized_score, raw_upper_bound=problem['raw_upper_bound'],
                           score_numerator_upper_bound=problem['raw_upper_bound']*1000,
                           long_arithmetic_safe=problem['raw_upper_bound']*1000 <= 2**63-1,
                           judge_mode=mode))
        write_csv(AREA/'coverage-33.csv', rows)
        write_csv(AREA/'input-support-audit.csv', audits)
        print(f"Coverage {len(rows)}/33 {path.name}: {score} {mode}", flush=True)
    if len(rows) != 33:
        raise AssertionError('Coverage incomplete')


def source_hash():
    hasher = hashlib.sha256()
    for path in sorted(Path('src/main/java').rglob('*.java')):
        hasher.update(path.as_posix().encode())
        hasher.update(path.read_bytes())
    return hasher.hexdigest()


def canonical_source_hash():
    hasher=hashlib.sha256()
    for path in sorted(Path('src/main/java').rglob('*.java')):
        hasher.update(path.as_posix().encode())
        hasher.update(path.read_bytes().replace(b'\r\n',b'\n'))
    return hasher.hexdigest()


def run_case(args, item, mode, seconds, label, extra=(), incumbent=None):
    config = dict(mode=mode, seconds=seconds, extra=list(extra), incumbent=digest(incumbent) if incumbent else None,
                  source_sha256=source_hash(), input_sha256=item['sha256'])
    key = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()[:16]
    directory = AREA/'runs'/f'{label}-{key}'
    record = directory/'validated.json'
    path = args.inputs/item['relative_path']
    problem = parse(path)
    if digest(path) != item['sha256']:
        raise ValueError('Input changed')
    if record.exists():
        result = json.loads(record.read_text())
        if digest(result['solution']) != result['solution_sha256']:
            raise ValueError('Archived output changed')
        raw, score = checked(problem, path, Path(result['solution']), judge_for(item,args))
        if (raw,score) != (result['raw_objective'],result['score']):
            raise ValueError('Cached score changed')
        print(f"Resumed {item['filename']} {label}: {score}", flush=True)
        return result
    directory.mkdir(parents=True, exist_ok=True)
    solution, metadata, trace = directory/'solution.txt', directory/'metadata.json', directory/'trace.csv'
    command = ['java','-Xmx2g','-cp','out','com.hashcode.streaming.Main',mode,str(path),str(solution),
               '--seconds',str(seconds),'--metadata',str(metadata),'--trace',str(trace),*extra]
    if incumbent:
        command += ['--incumbent',str(incumbent)]
    before = time.perf_counter()
    with (directory/'process.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=seconds+60, check=True)
    wall = time.perf_counter()-before
    result = json.loads(metadata.read_text())
    raw, score = checked(problem,path,solution,judge_for(item,args))
    if (raw,score) != (result['raw_objective'],result['score']):
        raise AssertionError('Java/Python/C++ disagreement')
    result.update(instance=item['filename'], label=label, input_sha256=item['sha256'], configuration=config,
                  solution=solution.as_posix(), solution_sha256=digest(solution), wall_seconds=round(wall,6),
                  trace=trace.as_posix(), command=command, judge_sha256=digest(judge_for(item,args)))
    record.write_text(json.dumps(result,indent=2)+'\n')
    print(f"{item['filename']} {label}: {score}, {result['algorithm_ms']/1000:.3f}s, {result['termination']}",flush=True)
    return result


DEVELOPMENT = ['8_2_8_2.in','dense2.in','medium_mixed_lambda.in','regional_instance.in']
HELDOUT = ['3860_246_84901_66.in','dense.in','medium_flat_sparse_lambda.in','zipF_instance.in']


def compare(args):
    rows=[]
    inventory_rows = read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    for group,names in [('development',DEVELOPMENT),('heldout',HELDOUT)]:
        if args.group != 'both' and group != args.group:
            continue
        for name in names:
            item=next(r for r in inventory_rows if r['filename']==name)
            for seconds in [1,3]:
                for mode in ['portfolio','adaptive','competition']:
                    for repeat in range(3):
                        result=run_case(args,item,mode,seconds,f'{group}-{mode}-{seconds}s-r{repeat}')
                        rows.append(dict(group=group, budget_seconds=seconds, repeat=repeat,
                                         **{k:result[k] for k in ['instance','mode','score','raw_objective','algorithm_ms',
                                              'construction_ms','search_ms','distinct_starts','accepted','termination','solution','trace']}))
                        write_csv(AREA/f'{args.group}-comparison.csv',rows)


def ablation(args):
    rows=[]
    settings=[('adaptive',[]),('single-dynamic',['--starts','dynamic']),('single-static',['--starts','static']),
              ('single-zero',['--starts','zero']),('no-repack',['--no-repack']),('no-exchange',['--no-exchange']),
              ('competition',[]),('no-perturb',['--no-diversify'])]
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    for name in ['dense2.in','medium_mixed_lambda.in','regional_instance.in']:
        item=next(r for r in items if r['filename']==name)
        for label,extra in settings:
            mode='competition' if label in ['competition','no-perturb'] else 'adaptive'
            result=run_case(args,item,mode,3,'ablation-'+label,extra)
            rows.append(dict(configuration=label,**{k:result[k] for k in ['instance','score','raw_objective',
                              'algorithm_ms','construction_ms','search_ms','accepted','termination','solution']}))
            write_csv(AREA/'ablation.csv',rows)


def freeze(args):
    path=AREA/'solver-configuration.json'
    if path.exists():
        raise ValueError('Policy already frozen; do not overwrite it')
    rows=read_csv(AREA/'development-comparison.csv')
    if len(rows)!=4*2*3*3:
        raise ValueError('Development comparison incomplete')
    ranking=[]
    for mode in ['portfolio','adaptive','competition']:
        deficits=[]
        for name in DEVELOPMENT:
            subset=[r for r in rows if r['instance']==name and r['budget_seconds']=='3']
            best=max(statistics.median(int(r['score']) for r in subset if r['mode']==m)
                     for m in ['portfolio','adaptive','competition'])
            own=statistics.median(int(r['score']) for r in subset if r['mode']==mode)
            deficits.append((best-own)/max(1,best))
        ranking.append(dict(mode=mode,mean_relative_deficit=statistics.mean(deficits)))
    ranking.sort(key=lambda r:(r['mean_relative_deficit'],['adaptive','portfolio','competition'].index(r['mode'])))
    # Default remains a genuine adaptive engine; a fixed portfolio is a reference, not the deliverable.
    if ranking[0]['mode']=='portfolio' and ranking[0]['mean_relative_deficit']<next(r['mean_relative_deficit'] for r in ranking if r['mode']=='adaptive'):
        raise ValueError('Fixed portfolio dominates development: investigate policy before final freeze')
    configuration=dict(default_mode='adaptive',standard_seconds=10,competition_seconds=30,
        development=DEVELOPMENT,heldout=HELDOUT,development_ranking=ranking,
        source_sha256=source_hash(),canonical_lf_source_sha256=canonical_source_hash(),seed=20170317,construction_budget_fraction=0.6,
        constructors=['dynamic weight 1','zero regret','indexed static weight 1'],
        initial_repair_passes=dict(portfolio=10,adaptive_dynamic_zero=1,adaptive_static=10,competition_dynamic_zero=1,competition_static=10),
        repack_candidates=dict(portfolio=64,adaptive=64,competition=128),
        exchange=dict(pair_limit=1000,videos_per_cache=64,rounds=5,competition_rounds=20),
        adaptive_policy='quality + smoothed exact gain/ms + exploration bonus; every eighth unit explores least-tried live start',
        diversification='competition only: remove one eighth from a seeded cache, exact repair, retain global best; stop after 8 failures',
        arithmetic='checked signed long objective bound; BigInteger score numerator; independent Python integers and C++ wide judge',
        archive_policy='input SHA256 matching only for incumbents, never solver strategy selection',
        environment=dict(os=platform.platform(),python=platform.python_version(),logical_processors=os.cpu_count(),
                         java=subprocess.run(['java','-version'],text=True,capture_output=True).stderr.strip()),
        source_revision=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        frozen_before_heldout=True)
    path.write_text(json.dumps(configuration,indent=2)+'\n')
    print(json.dumps(configuration,indent=2),flush=True)


def incumbents(args, items):
    coverage_rows={r['instance']:r for r in read_csv(AREA/'coverage-33.csv')}
    historical={}
    for original in Path('examples').glob('*.in'):
        outputs=list(Path('experiments/final-best-solutions').glob(original.stem+'-*.txt'))
        if outputs:historical.setdefault(canonical_input_digest(original),[]).extend(outputs)
    found={}
    for item in items:
        path=args.inputs/item['relative_path']; problem=parse(path)
        row=coverage_rows[item['filename']]
        candidates=[Path(row['solution'])]
        # Old official records are admitted only if their actual input content matches this input.
        matched=historical.get(canonical_input_digest(path),[])
        canonical_match=bool(matched)
        candidates+=matched
        scored=[(*checked(problem,path,solution,judge_for(item,args)),solution) for solution in candidates]
        raw,score,solution=max(scored,key=lambda r:r[0])
        found[item['filename']]=dict(raw=raw,score=score,solution=solution.as_posix(),input_sha256=item['sha256'],
                                    canonical_lf_input_sha256=canonical_input_digest(path),
                                    historical_input_lf_match=canonical_match,baseline_version=2)
    return found


def final_run(args):
    config=json.loads((AREA/'solver-configuration.json').read_text())
    if config['source_sha256']!=source_hash() and config.get('canonical_lf_source_sha256')!=canonical_source_hash():
        raise ValueError('Solver source changed after freeze')
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    # Freeze historical predecessors once, before any new final search can improve an archive.
    prior_path=AREA/'previous-incumbents.json'
    if prior_path.exists() and next(iter(json.loads(prior_path.read_text()).values())).get('baseline_version')!=2:
        # Preserve the initial byte-only manifest. Only broaden archive binding, never strategy selection.
        preserved=AREA/'previous-incumbents-initial-byte-match.json'
        if not preserved.exists():shutil.copyfile(prior_path,preserved)
        prior_path.write_text(json.dumps(incumbents(args,items),indent=2)+'\n')
    if not prior_path.exists():prior_path.write_text(json.dumps(incumbents(args,items),indent=2)+'\n')
    prior=json.loads(prior_path.read_text())
    rows=[]
    for item in items:
        name=item['filename']; path=args.inputs/item['relative_path']; problem=parse(path)
        fresh=run_case(args,item,config['default_mode'],config['standard_seconds'],'final-fresh')
        # Incumbent mode is explicitly separate; it is not fresh generalization evidence.
        continuation=run_case(args,item,'competition',10,'final-incumbent',incumbent=Path(prior[name]['solution']))
        candidates=[(prior[name]['raw'],Path(prior[name]['solution']),'previous'),
                    (fresh['raw_objective'],Path(fresh['solution']),'fresh'),
                    (continuation['raw_objective'],Path(continuation['solution']),'incumbent-search')]
        # Keep all previously judged candidates, including useful exploratory or longer-budget outputs.
        for record in (AREA/'runs').glob('*/validated.json'):
            data=json.loads(record.read_text())
            if data['input_sha256']==item['sha256']:
                candidates.append((data['raw_objective'],Path(data['solution']),data['label']))
        best_raw,best_source,origin=max(candidates,key=lambda r:r[0])
        output=AREA/'best-solutions'/f"{int(item['order']):02d}-{path.stem}.txt"
        output.parent.mkdir(parents=True,exist_ok=True)
        if output.exists() and evaluate(problem,output)[0]>best_raw:raise AssertionError('Archive regression')
        if output!=best_source:shutil.copyfile(best_source,output)
        raw,score=checked(problem,path,output,judge_for(item,args))
        if raw!=best_raw:raise AssertionError('Archive metadata mismatch')
        # Independent original-vs-wide judge check wherever the supplied arithmetic is reliable.
        if item['judge_integer_safe']=='True' and old.judge(path,output,args.wide_judge)!=score:
            raise AssertionError('Judges disagree')
        normalized=Path('tmp/ultimate-normalized')/name
        if item['status']!='valid' and normalized.exists():
            if checked(parse(normalized),normalized,output,judge_for(item,args))[1]!=score:
                raise AssertionError('Derived normalized input differs')
        mode='EXTENDED_WIDE_JUDGE' if item['status']!='valid' else (
            'OFFICIAL_FORMAT_WIDE_JUDGE' if item['judge_integer_safe']=='False' else 'ORIGINAL_OFFICIAL')
        rows.append(dict(order=int(item['order']),instance=name,previous_score=prior[name]['score'],
                         new_fresh_score=fresh['score'],final_best_score=score,improvement=score-prior[name]['score'],
                         fresh_runtime_seconds=round(fresh['algorithm_ms']/1000,6),
                         incumbent_runtime_seconds=round(continuation['algorithm_ms']/1000,6),
                         judge_mode=mode,status='VALID_EXTENDED' if item['status']!='valid' else 'VALID_OFFICIAL_FORMAT',
                         origin=origin,input_sha256=item['sha256'],solution=output.as_posix(),solution_sha256=digest(output)))
        write_csv(AREA/'all-33-results.csv',rows)
        write_csv(AREA/'all-33-results.tsv',rows,delimiter='\t')
        (AREA/'all-33-scores-only.txt').write_text(''.join(str(r['final_best_score'])+'\n' for r in rows),encoding='ascii')
        write_csv(AREA/'scores-only-order.csv',[{k:r[k] for k in ['order','instance','judge_mode','status']} for r in rows])
        print(f"Final {len(rows)}/33 {name}: previous {prior[name]['score']}, fresh {fresh['score']}, archive {score}",flush=True)


def diagnostics(args):
    rows=[]
    for path in [Path('experiments/competitive-instance/competitive_streaming_videos.in'),
                 Path('experiments/competitive-instance-v2/best-v2.in'),
                 *sorted(Path('experiments/competitive-instance/candidates').glob('*.seed_29.in'))]:
        _,issues=inventory.inspect_input(path)
        if issues:raise ValueError(issues)
        item=dict(filename=path.name,relative_path=path.as_posix(),sha256=digest(path),judge_integer_safe='True')
        previous_inputs=args.inputs;args.inputs=Path('.')
        for mode in ['portfolio','adaptive','competition']:
            result=run_case(args,item,mode,3,'diagnostic-'+mode)
            rows.append({k:result[k] for k in ['instance','mode','score','algorithm_ms','accepted','termination','solution']})
            write_csv(AREA/'diagnostic-results.csv',rows)
        args.inputs=previous_inputs


def intensive(args):
    """Small, declared larger-budget check, not a per-instance policy change."""
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    rows=[]
    for name in ['videos_worth_spreading.in','kittens.in']:
        item=next(r for r in items if r['filename']==name)
        for seconds in [10,30]:
            for mode in ['portfolio','adaptive','competition']:
                result=run_case(args,item,mode,seconds,f'large-budget-{mode}-{seconds}s')
                rows.append(dict(budget_seconds=seconds,**{k:result[k] for k in ['instance','mode','score','algorithm_ms',
                            'construction_ms','search_ms','accepted','termination','solution']}))
                write_csv(AREA/'larger-budget-comparison.csv',rows)


def repeat_promising(args):
    rows=read_csv(AREA/'larger-budget-comparison.csv')
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    repeated=[]
    for name in ['videos_worth_spreading.in','kittens.in']:
        winner=max([r for r in rows if r['instance']==name],key=lambda r:(int(r['score']),-float(r['algorithm_ms'])))
        item=next(r for r in items if r['filename']==name)
        for repeat in range(2):
            result=run_case(args,item,winner['mode'],int(winner['budget_seconds']),f'promising-repeat-r{repeat}')
            repeated.append(dict(budget_seconds=winner['budget_seconds'],repeat=repeat,
                                 **{k:result[k] for k in ['instance','mode','score','algorithm_ms','accepted','termination','solution']}))
            write_csv(AREA/'promising-repeats.csv',repeated)


def unseen(args):
    """Post-freeze new seeds from the existing independent unclustered generator."""
    generator=load('ultimate_generic_generator',ROOT/'generate-experiments.py')
    directory=AREA/'unseen-inputs';directory.mkdir(exist_ok=True)
    specifications=[('sparse-flat',773177,2,0.6),('dense-skew',902117,24,1.4)]
    rows=[];manifest=[]
    original_inputs=args.inputs
    for name,seed,degree,alpha in specifications:
        path=directory/(name+'.in')
        scenario=generator.Scenario(name,'post-freeze-validation',videos=1200,endpoints=80,caches=30,
                                    descriptions=15000,degree=degree,alpha=alpha,capacity=2500)
        expected=generator.generate(Path('tmp')/('ultimate-unseen-'+name+'.in'),scenario,seed)
        generated=Path('tmp')/('ultimate-unseen-'+name+'.in')
        if path.exists() and digest(path)!=digest(generated):raise ValueError('Unseen input generation changed')
        if not path.exists():shutil.copyfile(generated,path)
        _,issues=inventory.inspect_input(path)
        if issues:raise ValueError(issues)
        manifest.append(dict(expected,file=path.as_posix(),role='new-seed same-generator heldout; not unseen-author evidence'))
        item=dict(filename=path.name,relative_path=path.as_posix(),sha256=digest(path),judge_integer_safe='True')
        args.inputs=Path('.')
        for mode in ['portfolio','adaptive','competition']:
            for repeat in range(3):
                result=run_case(args,item,mode,3,f'unseen-{mode}-r{repeat}')
                rows.append(dict(repeat=repeat,**{k:result[k] for k in ['instance','mode','score','algorithm_ms','accepted','termination','solution']}))
                write_csv(AREA/'unseen-seed-comparison.csv',rows)
        args.inputs=original_inputs
    (AREA/'unseen-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')


def polish(args):
    """Extend the capped incumbent searches; identical policy on both costly official cases."""
    rows=[]
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    final={r['instance']:r for r in read_csv(AREA/'all-33-results.csv')}
    for name in ['videos_worth_spreading.in','kittens.in']:
        item=next(r for r in items if r['filename']==name)
        result=run_case(args,item,'competition',30,'incumbent-polish-30s',
                        ['--starts','dynamic'],incumbent=Path(final[name]['solution']))
        rows.append(dict(previous_archive_score=final[name]['final_best_score'],
                         **{k:result[k] for k in ['instance','score','algorithm_ms','construction_ms','search_ms','accepted','termination','solution']}))
        write_csv(AREA/'incumbent-polish.csv',rows)


def validate(args):
    rows=read_csv(AREA/'all-33-results.csv')
    items=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    if len(rows)!=33 or [r['instance'] for r in rows]!=[r['filename'] for r in items]:
        raise AssertionError('Final table coverage/order mismatch')
    scores=(AREA/'all-33-scores-only.txt').read_text().splitlines()
    if scores!=[r['final_best_score'] for r in rows]:raise AssertionError('Scores-only mapping mismatch')
    results=[]
    for row,item in zip(rows,items):
        path=args.inputs/item['relative_path'];output=Path(row['solution'])
        if digest(path)!=row['input_sha256'] or digest(output)!=row['solution_sha256']:
            raise AssertionError('Input/output hash mismatch')
        raw,score=checked(parse(path),path,output,judge_for(item,args))
        if score!=int(row['final_best_score']) or score<int(row['previous_score']):
            raise AssertionError('Score mismatch or archive regression')
        wide=old.judge(path,output,args.wide_judge)
        if score!=wide:raise AssertionError('Wide judge mismatch')
        exact=old.judge(path,output,args.exact_judge)
        if score!=exact:raise AssertionError('Strict 128-bit judge mismatch')
        normalized_score=''
        if item['status']!='valid':
            derived=Path('tmp/ultimate-normalized')/path.name
            if not derived.exists():exact_normalize(parse(path),derived)
            normalized_score=checked(parse(derived),derived,output,judge_for(item,args))[1]
            if normalized_score!=score:raise AssertionError('Normalization mismatch')
        results.append(dict(order=row['order'],instance=row['instance'],score=score,raw_objective=raw,
                            python_exact='PASS',cpp_judge='PASS',wide_cpp='PASS',strict_128_cpp='PASS',
                            normalized_score=normalized_score,sha256=row['solution_sha256']))
        print(f"Independent replay {len(results)}/33 {path.name}: {score}",flush=True)
        write_csv(AREA/'independent-validation.csv',results)
    (AREA/'validation-manifest.json').write_text(json.dumps(dict(
        results=len(results),original_judge_binary_sha256=digest(args.judge),
        wide_judge_binary_sha256=digest(args.wide_judge),strict_judge_binary_sha256=digest(args.exact_judge),
        wide_judge_source_sha256=digest(Path('experiments/promo-benchmark/judgeHashCode2017-wide.cpp')),
        strict_judge_source_sha256=digest(AREA/'judge-exact.cpp'),
        python_evaluator_sha256=digest(Path('ultimate-experiments.py')),
        checks='input/output hashes, independent arbitrary-precision objective, C++ cross-checks, normalization equivalence, archive monotonicity'
    ),indent=2)+'\n')


def summary(args):
    comparison=[]
    for path in [AREA/'development-comparison.csv',AREA/'heldout-comparison.csv']:
        if path.exists():comparison+=read_csv(path)
    write_csv(AREA/'solver-comparison.csv',comparison)
    medians=[]
    for group in ['development','heldout']:
        for budget in [1,3]:
            for name in (DEVELOPMENT if group=='development' else HELDOUT):
                for mode in ['portfolio','adaptive','competition']:
                    subset=[r for r in comparison if r['group']==group and r['instance']==name
                            and r['budget_seconds']==str(budget) and r['mode']==mode]
                    if not subset:continue
                    medians.append(dict(group=group,instance=name,budget_seconds=budget,mode=mode,repeats=len(subset),
                         median_score=statistics.median(int(r['score']) for r in subset),
                         min_score=min(int(r['score']) for r in subset),max_score=max(int(r['score']) for r in subset),
                         median_algorithm_seconds=round(statistics.median(float(r['algorithm_ms']) for r in subset)/1000,6)))
    write_csv(AREA/'comparison-medians.csv',medians)
    records=[json.loads(path.read_text()) for path in (AREA/'runs').glob('*/validated.json')]
    totals={r['filename']:int(r['total_requests']) for r in read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))}
    frontier=[];profiles=[]
    frozen=json.loads((AREA/'solver-configuration.json').read_text())
    accepted_sources={source_hash()}
    if frozen.get('canonical_lf_source_sha256')==canonical_source_hash():accepted_sources.add(frozen['source_sha256'])
    for result in records:
        if result['configuration']['source_sha256'] not in accepted_sources:continue
        events=read_csv(result['trace']); total=totals.get(result['instance'])
        if total is None:continue
        duration=result['algorithm_ms']
        for seconds in [0.1,0.5,1,3,5,10,30]:
            if duration<seconds*1000:continue
            reached=[e for e in events if float(e['elapsed_ms'])<=seconds*1000]
            raw=max([int(e['best_raw']) for e in reached],default=0)
            frontier.append(dict(instance=result['instance'],configuration=result['label'],mode=result['mode'],
                                 checkpoint_seconds=seconds,score=raw*1000//total,raw_objective=raw,
                                 checkpoint_kind='reached_time'))
        # Small runs may finish before the first reporting mark: retain their real final point.
        frontier.append(dict(instance=result['instance'],configuration=result['label'],mode=result['mode'],
                             checkpoint_seconds=round(duration/1000,6),score=result['score'],
                             raw_objective=result['raw_objective'],checkpoint_kind='actual_finish'))
        previous=0;operations=defaultdict(lambda:[0,0,0])
        for event in events:
            elapsed=float(event['elapsed_ms']);operation=event['operation']
            operations[operation][0]+=max(0,elapsed-previous)
            operations[operation][1]+=int(event['delta'])
            operations[operation][2]+=int(event['accepted'])
            previous=elapsed
        for operation,(ms,delta,moves) in operations.items():
            profiles.append(dict(instance=result['instance'],configuration=result['label'],operation=operation,
                                 batch_elapsed_ms=round(ms,3),working_raw_delta=delta,accepted=moves))
    write_csv(AREA/'score-time-frontier.csv',frontier)
    write_csv(AREA/'operation-profiles.csv',profiles)
    final=read_csv(AREA/'all-33-results.csv')
    features=read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))
    for item in features:
        item['evaluation_role']='development' if item['filename'] in DEVELOPMENT else 'heldout' if item['filename'] in HELDOUT else 'final_suite'
        item['official_dataset_duplicate']=str((Path('examples')/item['filename']).exists())
    write_csv(AREA/'instance-features.csv',features)
    def metrics(column):
        differences=[int(r[column])-int(r['previous_score']) for r in final]
        relative=[d/max(1,int(r['previous_score']))*100 for d,r in zip(differences,final)]
        return dict(improved=sum(d>0 for d in differences),tied=sum(d==0 for d in differences),regressed=sum(d<0 for d in differences),
                    total_gain=sum(differences),mean_relative_percent=statistics.mean(relative),
                    median_relative_percent=statistics.median(relative),worst_relative_percent=min(relative))
    stats=dict(fresh=metrics('new_fresh_score'),archive=metrics('final_best_score'),
               final_score_sum=sum(int(r['final_best_score']) for r in final),
               fresh_algorithm_seconds=sum(float(r['fresh_runtime_seconds']) for r in final),
               incumbent_algorithm_seconds=sum(float(r['incumbent_runtime_seconds']) for r in final),
               coverage=len(final),original_judge=sum(r['judge_mode']=='ORIGINAL_OFFICIAL' for r in final),
               wide_official_format=sum(r['judge_mode']=='OFFICIAL_FORMAT_WIDE_JUDGE' for r in final),
               extended=sum(r['judge_mode']=='EXTENDED_WIDE_JUDGE' for r in final))
    (AREA/'summary.json').write_text(json.dumps(stats,indent=2)+'\n')
    print(json.dumps(stats,indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['coverage','compare','ablation','freeze','run','diagnostics','validate','summary','intensive','repeat','unseen','polish'])
    parser.add_argument('--inputs', type=Path, default=Path('tmp/promo-inputs'))
    parser.add_argument('--judge', type=Path, default=Path('tmp/judgeHashCode2017.exe'))
    parser.add_argument('--wide-judge', type=Path, default=Path('tmp/judgeHashCode2017-wide.exe'))
    parser.add_argument('--exact-judge', type=Path, default=Path('tmp/judge-exact.exe'))
    parser.add_argument('--group', choices=['development','heldout','both'], default='development')
    args = parser.parse_args()
    os.chdir(ROOT)
    {'coverage':coverage,'compare':compare,'ablation':ablation,'freeze':freeze,'run':final_run,
     'diagnostics':diagnostics,'validate':validate,'summary':summary,'intensive':intensive,'repeat':repeat_promising,
     'unseen':unseen,'polish':polish}[args.action](args)


if __name__ == '__main__':
    main()
