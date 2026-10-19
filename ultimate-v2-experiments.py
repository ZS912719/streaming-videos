#!/usr/bin/env python3
"""Isolated V2 experiment ledger; historical results are read-only."""
import argparse
import collections
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path
import importlib.util
import sys
import statistics
import random
import platform
import os

spec = importlib.util.spec_from_file_location('ultimate_v1', 'ultimate-experiments.py')
u = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = u
spec.loader.exec_module(u)
AREA = Path('experiments/ultimate-solver-v2')
V1 = Path('experiments/ultimate-solver')
u.AREA = AREA


def legacy_source_hash():
    """Reconstruct the frozen V1 tree, excluding only V2 files and its CLI route."""
    hasher=hashlib.sha256()
    route='''        if (args.length > 0 && args[0].equals("ultimate-v2")) {
            try { UltimateV2Main.main(args); }
            catch (Exception error) { error.printStackTrace(System.err); System.exit(1); }
            return;
        }
'''
    for path in sorted(Path('src/main/java').rglob('*.java')):
        if path.name in {'UltimateV2Solver.java','UltimateV2Main.java','BenefitIndex.java'}:continue
        data=path.read_bytes().replace(b'\r\n',b'\n')
        if path.name=='Main.java':
            assert route.encode() in data
            data=data.replace(route.encode(),b'')
        hasher.update(path.as_posix().encode());hasher.update(data)
    expected=json.loads((V1/'solver-configuration.json').read_text())['canonical_lf_source_sha256']
    assert hasher.hexdigest()==expected,'Historical solver tree changed'
    return hasher.hexdigest()


def snapshot(args):
    version=u.source_hash();folder=AREA/'source-versions'/version
    originals=[p for p in Path('src/main/java').rglob('*.java') if p.name in {'Main.java','UltimateV2Main.java','UltimateV2Solver.java','BenefitIndex.java'}]
    folder.mkdir(parents=True,exist_ok=True)
    for path in originals:
        dest=folder/path.name
        if dest.exists():assert dest.read_bytes()==path.read_bytes()
        else:shutil.copyfile(path,dest)
    manifest=dict(source_sha256=version,canonical_source_sha256=u.canonical_source_hash(),
                  files={p.as_posix():p.name for p in originals},removed=[],legacy_source_sha256=legacy_source_hash())
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Preserved source version '+version,flush=True)


def overlay_hash(folder,canonical=False):
    manifest=json.loads((folder/'manifest.json').read_text());hasher=hashlib.sha256()
    for path in sorted(Path('src/main/java').rglob('*.java')):
        relative=path.as_posix()
        if relative in manifest.get('removed',[]):continue
        source=folder/manifest['files'][relative] if relative in manifest['files'] else path
        data=source.read_bytes()
        if canonical:data=data.replace(b'\r\n',b'\n')
        hasher.update(relative.encode());hasher.update(data)
    return hasher.hexdigest()


def build_version(args):
    if not args.version:raise ValueError('--version hash required')
    if not all(c in '0123456789abcdef' for c in args.version) or len(args.version)!=64:raise ValueError('Full SHA256 required')
    folder=AREA/'source-versions'/args.version;manifest=json.loads((folder/'manifest.json').read_text())
    assert overlay_hash(folder)==manifest['source_sha256'] or overlay_hash(folder,True)==manifest['canonical_source_sha256']
    assert legacy_source_hash()==manifest['legacy_source_sha256']
    sources=[]
    for path in Path('src/main/java').rglob('*.java'):
        relative=path.as_posix()
        if relative in manifest.get('removed',[]):continue
        sources.append(str(folder/manifest['files'][relative] if relative in manifest['files'] else path))
    output=Path('tmp/v2-source-replay')/args.version;output.mkdir(parents=True,exist_ok=True)
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(output),*sources],check=True)
    print('Version compiled without changing the working tree: '+str(output),flush=True)


def items():
    return u.read_csv(Path('experiments/promo-benchmark/instance-inventory.csv'))


def diagnosis(args):
    previous = u.read_csv(V1/'all-33-results.csv')
    regressions = [r for r in previous if int(r['new_fresh_score']) < int(r['previous_score'])]
    assert len(regressions) == 7
    rows = []
    for row in regressions:
        matches = []
        for path in (V1/'runs').glob('*/validated.json'):
            r = json.loads(path.read_text())
            if r['instance'] == row['instance'] and r['label'] == 'fresh-default':
                matches.append(r)
        if not matches:
            for path in (V1/'runs').glob('*/validated.json'):
                r = json.loads(path.read_text())
                if r['instance'] == row['instance'] and r['configuration']['seconds'] == 10 and not r['incumbent'] and r['score'] == int(row['new_fresh_score']):
                    matches.append(r)
        r = matches[0]
        allocations = collections.defaultdict(lambda: [0., 0, 0])
        before = 0.
        for event in u.read_csv(Path(r['trace'])):
            elapsed = float(event['elapsed_ms'])
            key = (event['start'], event['operation'])
            a = allocations[key]
            a[0] += elapsed-before
            a[1] += int(event['accepted'])
            a[2] += int(event['delta'])
            before = elapsed
        for (start, operation), (ms, moves, gain) in allocations.items():
            rows.append(dict(instance=row['instance'], previous_score=row['previous_score'], v1_fresh_score=row['new_fresh_score'], start=start, operation=operation, allocated_ms=round(ms,3), moves=moves, raw_gain=gain, termination=r['termination'], trace=r['trace']))
    u.write_csv(AREA/'regression-v1-allocation.csv', rows)
    for item in items():
        if item['filename'] not in {r['instance'] for r in regressions}: continue
        # Reproduce the exact frozen predecessor rather than silently credit its archive.
        r = u.old.experiment(args.inputs/item['relative_path'], 'v2-regression-replay',
                             u.old.CANDIDATES['dynamic-repack-exchange'], u.judge_for(item,args),
                             timeout=240, area=AREA/'reference-replays')
        if item['filename'] not in {'kittens.in','videos_worth_spreading.in'}:
            expected = next(int(x['previous_score']) for x in regressions if x['instance']==item['filename'])
            assert r['official_score'] == expected
    for name in ['3860_246_84901_66.in','videos_worth_spreading.in','custom_universallambda42_asymmetric.in']:
        item = next(x for x in items() if x['filename']==name)
        for mode in ['ultimate','portfolio','competition']:
            u.run_case(args,item,mode,30,'controlled-v1-'+mode)


def selected(args):
    return [x for x in items() if not args.names or x['filename'] in args.names.split(',')]


def run_case(args,item,mode,seconds,label,extra=(),incumbent=None):
    config=dict(mode=mode,seconds=seconds,extra=list(extra),incumbent=u.digest(incumbent) if incumbent else None,
                source_sha256=u.source_hash(),input_sha256=item['sha256'],runner_version=2,heap='4g')
    key=hashlib.sha256(json.dumps(config,sort_keys=True).encode()).hexdigest()[:16]
    directory=AREA/'runs'/f'{label}-{key}';record=directory/'validated.json'
    path=args.inputs/item['relative_path'];assert u.digest(path)==item['sha256']
    problem=u.parse(path)
    if record.exists():
        result=json.loads(record.read_text());assert u.digest(result['solution'])==result['solution_sha256']
        assert u.checked(problem,path,Path(result['solution']),u.judge_for(item,args))==(result['raw_objective'],result['score'])
        for cp in result.get('checkpoints',[]):
            assert u.digest(cp['solution'])==cp['solution_sha256']
            assert u.checked(problem,path,Path(cp['solution']),u.judge_for(item,args))==(int(cp['raw_objective']),int(cp['score']))
        print(f"Resumed {item['filename']} {label}: {result['score']}",flush=True);return result
    directory.mkdir(parents=True,exist_ok=True)
    solution,metadata,trace=[directory/f for f in ['solution.txt','metadata.json','trace.csv']]
    command=['java','-Xmx4g','-cp','out','com.hashcode.streaming.Main',mode,str(path),str(solution),
             '--seconds',str(seconds),'--metadata',str(metadata),'--trace',str(trace),*extra]
    if mode=='ultimate-v2' and '--max-units' not in extra:command+=['--checkpoints',str(directory/'checkpoints')]
    if incumbent:command+=['--incumbent',str(incumbent)]
    before=time.perf_counter()
    with (directory/'process.log').open('w') as log:
        subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=seconds+60,check=True)
    wall=time.perf_counter()-before;result=json.loads(metadata.read_text())
    before=time.perf_counter();raw,score=u.checked(problem,path,solution,u.judge_for(item,args));judging=time.perf_counter()-before
    assert (raw,score)==(result['raw_objective'],result['score'])
    result.update(instance=item['filename'],label=label,input_sha256=item['sha256'],configuration=config,
                  solution=solution.as_posix(),solution_sha256=u.digest(solution),wall_seconds=round(wall,6),
                  independent_validation_seconds=round(judging,6),trace=trace.as_posix(),command=command,
                  judge_sha256=u.digest(u.judge_for(item,args)))
    checkpoint_file=directory/'checkpoints'/'checkpoints.csv'
    if checkpoint_file.exists():
        result['checkpoints']=[]
        for row in u.read_csv(checkpoint_file):
            file=checkpoint_file.parent/row['solution']
            assert u.checked(problem,path,file,u.judge_for(item,args))==(int(row['raw_objective']),int(row['score']))
            row.update(solution=file.as_posix(),solution_sha256=u.digest(file))
            result['checkpoints'].append(row)
    record.write_text(json.dumps(result,indent=2)+'\n')
    print(f"{item['filename']} {label}: {score}, {result['algorithm_ms']/1000:.3f}s algorithm, {wall:.3f}s wall, {result['termination']}",flush=True)
    return result


def pilot(args):
    rows=[]
    for item in selected(args):
        for seconds in map(int,args.budgets.split(',')):
            for policy in args.policies.split(','):
                for repeat in range(args.repeats):
                    extra=['--policy',policy,*args.extra]
                    r=run_case(args,item,'ultimate-v2',seconds,f'pilot-{args.label}-{policy}-{seconds}-r{repeat}',extra)
                    rows.append(flat(r))
                    u.write_csv(AREA/f'pilot-{args.label}.csv',rows)


def compare_v1(args):
    """Fresh legacy default at the final cap, without rewriting the V1 archive."""
    frozen();legacy_source_hash()
    rows=[]
    for item in selected(args):
        result=run_case(args,item,'ultimate',180,'matched-v1-180')
        rows.append(flat(result))
        u.write_csv(AREA/'matched-v1-180-results.csv',rows)


def references(args):
    graph=json.loads(Path('experiments/final-ocag-ls-configurations.json').read_text())
    nodes={r['run_id']:r for r in graph['runs']};by_output={r['output_path']:r['run_id'] for r in graph['runs']}
    folder=AREA/'record-replays';folder.mkdir(parents=True,exist_ok=True)
    completed={};rows=[]
    def visit(run_id):
        if run_id in completed:return completed[run_id]
        node=nodes[run_id];mode=node['mode'];start=node['start_solution']
        if mode!='dynamic' and start in by_output:start=visit(by_output[start])
        item=next(x for x in items() if x['filename']==node['dataset']+'.in')
        inp=args.inputs/item['relative_path'];stem=folder/run_id
        output,metrics,trace=[Path(str(stem)+s) for s in ['.txt','.metrics.csv','.trace.csv']]
        cached=Path(str(stem)+'.validated.json')
        if cached.exists():
            row=json.loads(cached.read_text());assert u.digest(output)==row['solution_sha256']
        else:
            if mode=='local':
                command=['java','-Xmx4g','-cp','out','com.hashcode.streaming.experiments.LocalSearchTuningRunner',str(inp),str(start),str(output),str(metrics),str(trace),node['passes'],node['moves'],node['selection'],node['cache_order'],'forward']
            else:
                parameters={'dynamic':[node['weight'],node['regret_mode']],
                            'exchange':[node['pairs'],node['video_limit'],node['rounds'],node['first'].lower(),node['time_limit_ms']],
                            'packing':[node['passes'],node.get('candidate_limit','64'),node.get('neighborhood','both')],
                            'repack':[node['passes'],node.get('candidate_limit','64')]}[mode]
                command=['java','-Xmx4g','-cp','out','com.hashcode.streaming.experiments.FinalOptimizationRunner',mode,str(inp),str(start),str(output),str(metrics),str(trace),*parameters]
            before=time.perf_counter()
            with Path(str(stem)+'.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=300,check=True)
            m=u.read_csv(metrics)[0]
            raw=int(m['final_saved_latency'] if mode=='local' else m['raw_objective'])
            row=dict(run_id=run_id,instance=item['filename'],mode=mode,raw_objective=raw,score=int(node['official_score']),
                     algorithm_ms=float(m['search_ms'] if mode=='local' else m['algorithm_ms']),wall_seconds=time.perf_counter()-before,
                     solution=output.as_posix(),solution_sha256=u.digest(output),command=command,input_sha256=item['sha256'])
            assert raw==int(node['raw_objective'])
            cached.write_text(json.dumps(row,indent=2)+'\n')
        assert u.checked(u.parse(inp),inp,output,u.judge_for(item,args))==(int(node['raw_objective']),int(node['official_score']))
        rows.append(row);u.write_csv(folder/'stage-results.csv',rows)
        print(f"Reference {run_id}: {row['score']} ({row['algorithm_ms']/1000:.3f}s)",flush=True)
        completed[run_id]=output.as_posix();return completed[run_id]
    for dataset in ['videos_worth_spreading','kittens']:visit(graph['record_targets'][dataset])


def snapshot_test(args):
    directory=AREA/'snapshot-copy-comparison';directory.mkdir(parents=True,exist_ok=True)
    item=next(x for x in items() if x['filename']=='kittens.in');inp=args.inputs/item['relative_path'];p=u.parse(inp)
    rows=[]
    for variant,classpath in [('before','tmp/v2-before-snapshot-opt'),('after','tmp/v2-test-classes')]:
        for repeat in range(3):
            output=directory/f'{variant}-{repeat}.txt';metadata=directory/f'{variant}-{repeat}.json'
            command=['java','-Xmx4g','-cp',classpath,'com.hashcode.streaming.Main','ultimate-v2',str(inp),str(output),
                     '--seconds','300','--max-units','3000','--no-packing','--policy','fixed','--metadata',str(metadata)]
            if not metadata.exists():
                subprocess.run(command,stdout=subprocess.DEVNULL,check=True,timeout=120)
            r=json.loads(metadata.read_text());assert u.checked(p,inp,output,args.judge)==(r['raw_objective'],r['score'])
            rows.append(dict(variant=variant,repeat=repeat,score=r['score'],raw_objective=r['raw_objective'],algorithm_seconds=r['algorithm_ms']/1000,solution_sha256=u.digest(output)))
            u.write_csv(directory/'results.csv',rows)
            print(rows[-1],flush=True)
    assert len({r['raw_objective'] for r in rows})==1


def index_test(args):
    rows=[]
    for name,units,disable in [('kittens.in',6000,'--no-benefit-index'),('videos_worth_spreading.in',60000,'--no-size-pruning')]:
        item=next(x for x in items() if x['filename']==name)
        group=[]
        for flag in [disable,'optimized']:
            for repeat in range(3):
                extra=['--policy','fixed','--max-units',str(units)]
                if name=='kittens.in':extra+=['--no-packing']
                if flag!='optimized':extra+=[flag]
                r=run_case(args,item,'ultimate-v2',300,f'index-{flag}-r{repeat}',extra)
                group.append(r);rows.append(flat(r));u.write_csv(AREA/'index-performance.csv',rows)
        assert len({r['raw_objective'] for r in group})==1,'Index/pruning changed fixed-work objective'
        assert len({r['solution_sha256'] for r in group})==1,'Index/pruning changed placement/ties'


def heldout(args):
    config=frozen();folder=AREA/'heldout-inputs';folder.mkdir(exist_ok=True)
    manifest=[];rows=[]
    # Two topology families declared independently of scores, not just new seeds
    # of the earlier uniform-link generic generator.
    for family,seed in [('ring-overlap',173801),('hierarchical-hubs',772039)]:
        rng=random.Random(seed)
        v,e,c,capacity=(160,60,12,400) if family=='ring-overlap' else (200,80,24,1000)
        sizes=[rng.randint(10,30) if i%3 else rng.randint(65,120) for i in range(v)]
        endpoints=[];requests=[]
        for endpoint in range(e):
            if family=='ring-overlap':
                zone=endpoint%c;links={zone:rng.randint(20,100),(zone+1)%c:rng.randint(100,250),(zone+2)%c:rng.randint(250,450)}
            else:
                local=4+endpoint%20;links={local:rng.randint(20,80),endpoint%4:rng.randint(100,200),(endpoint+1)%4:rng.randint(300,450)}
            endpoints.append((rng.randint(800,4000),links))
            for i in range(70):
                video=rng.randrange(v//5) if i<30 else (endpoint*11+rng.randrange(v//3))%v
                requests.append((video,endpoint,rng.randint(1,10000)))
        lines=[f'{v} {e} {len(requests)} {c} {capacity}',' '.join(map(str,sizes))]
        for dc,links in endpoints:
            lines.append(f'{dc} {len(links)}');lines.extend(f'{cache} {lat}' for cache,lat in links.items())
        lines.extend(f'{video} {endpoint} {n}' for video,endpoint,n in requests)
        path=folder/(family+'.in');data=('\n'.join(lines)+'\n').encode('ascii')
        if path.exists():assert path.read_bytes()==data
        else:path.write_bytes(data)
        _,issues=u.inventory.inspect_input(path);assert not issues
        item=dict(filename=path.name,relative_path=path.as_posix(),sha256=u.digest(path),judge_integer_safe='True')
        local=argparse.Namespace(**vars(args));local.inputs=Path('.')
        manifest.append(dict(family=family,seed=seed,input_sha256=item['sha256'],frozen_policy_source=config['source_sha256']))
        for mode in ['ultimate','portfolio','competition']:
            rows.append(flat(run_case(local,item,mode,30,'heldout-v1-'+mode)))
        for policy in ['fixed','adaptive','intensive']:
            rows.append(flat(run_case(local,item,'ultimate-v2',30,'heldout-v2-'+policy,['--policy',policy,*config['extra']])))
        u.write_csv(AREA/'heldout-results.csv',rows)
    (AREA/'heldout-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')


def flat(r):
    return dict(instance=r['instance'],label=r['label'],mode=r['mode'],policy=r.get('policy',r['mode']),
                budget_seconds=r['configuration']['seconds'],score=r['score'],raw_objective=r['raw_objective'],
                algorithm_seconds=r['algorithm_ms']/1000,construction_seconds=r['construction_ms']/1000,
                search_seconds=r['search_ms']/1000,wall_seconds=r['wall_seconds'],accepted=r['accepted'],
                termination=r['termination'],incumbent=r['incumbent'],solution=r['solution'],
                solution_sha256=r['solution_sha256'],input_sha256=r['input_sha256'],trace=r['trace'],
                options=json.dumps(r['configuration']['extra']),source_sha256=r['configuration']['source_sha256'])


def summary(args):
    records=[json.loads(p.read_text()) for p in (AREA/'runs').glob('*/validated.json')]
    comparison=[dict(flat(r),budget_type='fixed_units' if '--max-units' in r['configuration']['extra'] else 'time_cap') for r in records]
    for path in (AREA/'reference-replays'/'runs').glob('*.json'):
        r=json.loads(path.read_text())
        comparison.append(dict(instance=r['instance'],label='frozen-promotion-replay',mode='frozen-promotion',policy='fixed',
                               budget_seconds=240,score=r['official_score'],raw_objective=r['raw_objective'],
                               algorithm_seconds=r['algorithm_ms']/1000,construction_seconds=r['construction_ms']/1000,
                               search_seconds=r['search_ms']/1000,wall_seconds=r['wall_seconds'],accepted='',termination=r['status'],
                               incumbent=False,solution=r['solution'],solution_sha256=r['solution_sha256'],input_sha256=r['input_sha256'],
                               trace='',options=json.dumps(r['parameters']),source_sha256=legacy_source_hash(),budget_type='fixed_work'))
    u.write_csv(AREA/'solver-comparison.csv',comparison)
    ablations=[flat(r) for r in records if r['mode']=='ultimate-v2' and any(x.startswith('--no-') or x=='--pattern' for x in r['configuration']['extra'])]
    if ablations:u.write_csv(AREA/'ablation-results.csv',ablations)
    frontier=[]
    for r in records:
        for cp in r.get('checkpoints',[]):
            frontier.append(dict(instance=r['instance'],label=r['label'],policy=r.get('policy',r['mode']),
                                 incumbent=r['incumbent'],budget_seconds=cp['budget_seconds'],score=cp['score'],
                                 observed_seconds=float(cp['observed_ms'])/1000,validation='INDEPENDENT_CPP',
                                 early_termination=cp['early_termination'],solution=cp['solution']))
        frontier.append(dict(instance=r['instance'],label=r['label'],policy=r.get('policy',r['mode']),
                             incumbent=r['incumbent'],budget_seconds=r['configuration']['seconds'],score=r['score'],
                             observed_seconds=r['wall_seconds'],validation='INDEPENDENT_CPP_FINAL',
                             early_termination=r['termination']!='time_budget',solution=r['solution']))
    u.write_csv(AREA/'score-time-frontier.csv',frontier)
    fresh_path=AREA/'all-33-fresh-results.csv';best_path=AREA/'all-33-best-known-results.csv'
    if not fresh_path.exists():return
    fresh=u.read_csv(fresh_path);best=u.read_csv(best_path) if best_path.exists() else []
    def metrics(rows,score,reference):
        changes=[(int(r[score])-int(r[reference]))/int(r[reference])*100 for r in rows]
        return dict(improved=sum(x>0 for x in changes),tied=sum(x==0 for x in changes),regressed=sum(x<0 for x in changes),
                    mean_relative_percent=statistics.mean(changes),median_relative_percent=statistics.median(changes),
                    worst_relative_percent=min(changes),point_gain=sum(int(r[score])-int(r[reference]) for r in rows),
                    algorithm_seconds=sum(float(r['algorithm_seconds']) for r in rows),wall_seconds=sum(float(r['wall_seconds']) for r in rows))
    data=dict(fresh_count=len(fresh),archive_count=len(best),fresh_vs_original=metrics(fresh,'score','previous_score'),
              fresh_vs_v1=metrics(fresh,'score','v1_fresh_score'),fresh_vs_records=metrics(fresh,'score','previous_best_score'))
    if best:data['archive_vs_records']=metrics(best,'score','previous_best_score')
    matched_path=AREA/'matched-v1-180-results.csv'
    if matched_path.exists():
        matched=u.read_csv(matched_path);by_name={r['instance']:r for r in matched}
        paired=[dict(r,matched_score=by_name[r['instance']]['score']) for r in fresh if r['instance'] in by_name]
        previous={r['instance']:r['previous_score'] for r in fresh}
        data['matched_v1_count']=len(matched)
        data['fresh_vs_v1_matched_180']=metrics(paired,'score','matched_score')
        data['matched_v1_vs_original']=metrics([dict(r,previous_score=previous[r['instance']]) for r in matched if r['instance'] in previous],'score','previous_score')
    (AREA/'summary.json').write_text(json.dumps(data,indent=2)+'\n')
    regressions={r['instance']:r for r in u.read_csv(V1/'all-33-results.csv') if int(r['new_fresh_score'])<int(r['previous_score'])}
    regression_rows=[]
    for r in records:
        if r['label']!='final-fresh' or r['instance'] not in regressions:continue
        previous=int(regressions[r['instance']]['previous_score']);p=u.parse(args.inputs/r['instance'])
        events=u.read_csv(Path(r['trace']));target=next((float(e['elapsed_ms'])/1000+r.get('engine_offset_ms',0)/1000 for e in events if int(e['best_raw'])*1000//p['total']>=previous),None)
        for cp in r.get('checkpoints',[]):
            regression_rows.append(dict(instance=r['instance'],previous_reference=previous,budget_seconds=cp['budget_seconds'],
                                        fresh_checkpoint_score=int(cp['score']),gap=int(cp['score'])-previous,time_to_reference_seconds=target,
                                        validation='INDEPENDENT_CPP',solution=cp['solution']))
        regression_rows.append(dict(instance=r['instance'],previous_reference=previous,budget_seconds=180,
                                    fresh_checkpoint_score=r['score'],gap=r['score']-previous,time_to_reference_seconds=target,
                                    validation='INDEPENDENT_CPP_FINAL',solution=r['solution']))
    if regression_rows:u.write_csv(AREA/'regression-results.csv',regression_rows)
    print(json.dumps(data,indent=2),flush=True)


def freeze(args):
    configuration=dict(policy=args.policies.split(',')[0],extra=args.extra,budget_seconds=180,seed=20170317,
                       constructors=['dynamic w1','zero','static w1'],protected_primary='LS10,guarded K64 p5,global exchange5,LS10',
                       complementary='dynamic LS50,packing10,global exchange20,LS50',
                       deep='K32/64/128/256 (intensive K512),global exchanges,LS; exact dependency-version repack skipping',
                       allocation=dict(primary_deep_trial_seconds=20,complement_trial_seconds=5,
                                       intensive_packing_trial_seconds=12,exploration_work_fraction=0.2,
                                       short_budget_trial_fractions=[0.5,0.1,0.25],
                                       rate_window='completed cache pass or global exchange round'),
                       source_sha256=u.source_hash(),canonical_source_sha256=u.canonical_source_hash(),
                       frozen_before_new_heldout=True,environment=dict(python=sys.version,os=platform.platform(),logical_processors=os.cpu_count(),java=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).strip()))
    target=AREA/'solver-configuration.json'
    if target.exists():
        assert json.loads(target.read_text())==configuration,'Frozen configuration changed'
    else: target.write_text(json.dumps(configuration,indent=2)+'\n')
    integrity=AREA/'historical-integrity.json'
    if not integrity.exists():
        paths=[p for p in Path('experiments').rglob('*') if p.is_file() and AREA not in p.parents and p.suffix in {'.txt','.out','.csv','.json','.md','.cpp','.in'}]
        paths += [p for p in Path('src/main/java/com/hashcode/streaming/solver').glob('*.java') if p.name not in {'UltimateV2Solver.java','BenefitIndex.java'}]
        source=dict(path_name=args.judge_source.name,sha256=u.digest(args.judge_source)) if args.judge_source else None
        integrity.write_text(json.dumps(dict(files={p.as_posix():u.digest(p) for p in paths},original_judge=source,legacy_canonical_source_sha256=legacy_source_hash()),indent=2)+'\n')


def frozen():
    config=json.loads((AREA/'solver-configuration.json').read_text())
    if config['source_sha256']!=u.source_hash() and config['canonical_source_sha256']!=u.canonical_source_hash():
        raise ValueError('Frozen V2 sources changed')
    return config


def final(args):
    config=frozen()
    previous={r['instance']:r for r in u.read_csv(V1/'all-33-results.csv')}
    rows=[]
    for item in selected(args):
        old=previous[item['filename']]
        incumbent=Path(old['solution'])
        assert u.digest(incumbent)==old['solution_sha256'] and item['sha256']==old['input_sha256']
        problem=u.parse(args.inputs/item['relative_path'])
        raw,score=u.checked(problem,args.inputs/item['relative_path'],incumbent,u.judge_for(item,args))
        assert score==int(old['final_best_score'])
        start_raw,start_score=raw,score
        if args.evaluation=='incumbent':
            for candidate_path in (AREA/'runs').glob('*/validated.json'):
                candidate=json.loads(candidate_path.read_text())
                # Fixed admission set makes resume idempotent. A completed continuation
                # is not silently turned into another 180-second continuation.
                if candidate['label']=='final-incumbent' or candidate['input_sha256']!=item['sha256'] or candidate['raw_objective']<=start_raw:continue
                source=Path(candidate['solution']);assert u.digest(source)==candidate['solution_sha256']
                cr,cs=u.checked(problem,args.inputs/item['relative_path'],source,u.judge_for(item,args))
                assert (cr,cs)==(candidate['raw_objective'],candidate['score'])
                incumbent,start_raw,start_score=source,cr,cs
        r=run_case(args,item,'ultimate-v2',180,'final-'+args.evaluation,
                     ['--policy',config['policy'],*config['extra']],incumbent if args.evaluation=='incumbent' else None)
        if args.evaluation=='incumbent':assert r['raw_objective']>=start_raw
        row=flat(r)
        row.update(order=int(item['order']),previous_score=int(old['previous_score']),v1_fresh_score=int(old['new_fresh_score']),previous_best_score=score,
                   judge_mode=old['judge_mode'],status=old['status'],previous_solution=old['solution'],
                   incumbent_start_score=start_score if args.evaluation=='incumbent' else '',
                   incumbent_start_solution=incumbent.as_posix() if args.evaluation=='incumbent' else '')
        rows.append(row)
        # Per-case rows allow complete resumable exports without marking missing cases completed.
        target=AREA/'final-rows'/f"{args.evaluation}-{int(item['order']):02d}.json"
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(row,indent=2)+'\n')
        export(args)


def export(args):
    admitted=collections.defaultdict(list)
    for candidate_path in (AREA/'runs').glob('*/validated.json'):
        candidate=json.loads(candidate_path.read_text());admitted[candidate['input_sha256']].append(candidate)
    for evaluation,name in [('fresh','all-33-fresh-results.csv'),('incumbent','all-33-best-known-results.csv')]:
        rows=[]
        for path in sorted((AREA/'final-rows').glob(evaluation+'-*.json')):
            row=json.loads(path.read_text())
            if evaluation=='incumbent':
                row['origin']='incumbent'
                fresh=AREA/'final-rows'/f"fresh-{row['order']:02d}.json"
                if fresh.exists():
                    f=json.loads(fresh.read_text())
                    if f['raw_objective']>row['raw_objective']:
                        for key in ['score','raw_objective','solution','solution_sha256']:row[key]=f[key]
                        row['origin']='fresh'
                    else:row['origin']='incumbent'
                # A later timed trajectory cannot erase a stronger validated pilot record.
                for candidate in admitted[row['input_sha256']]:
                    if candidate['raw_objective']<=row['raw_objective']:continue
                    assert u.digest(candidate['solution'])==candidate['solution_sha256']
                    for key in ['score','raw_objective','solution','solution_sha256']:row[key]=candidate[key]
                    row['origin']=candidate['label']
                fresh_scores=[candidate['score'] for candidate in admitted[row['input_sha256']] if not candidate.get('incumbent',False) and 'score' in candidate]
                row['best_fresh_trial_score']=max(fresh_scores,default=0)
                row['record_obtained']='previous' if row['score']<=row.get('previous_best_score',-1) else 'fresh' if row['best_fresh_trial_score']>=row['score'] else 'incumbent'
            archive=AREA/(evaluation+'-solutions')/f"{row['order']:02d}-{Path(row['instance']).stem}-{row['score']}-{row['solution_sha256'][:12]}.txt"
            archive.parent.mkdir(parents=True,exist_ok=True)
            if not archive.exists():shutil.copyfile(row['solution'],archive)
            assert u.digest(archive)==row['solution_sha256']
            row['archived_solution']=archive.as_posix()
            rows.append(row)
        if rows:
            u.write_csv(AREA/name,rows)
            if evaluation=='incumbent':
                (AREA/'scores-only.txt').write_text(''.join(str(r['score'])+'\n' for r in rows))


def validate(args):
    rows=[]
    for name in ['all-33-fresh-results.csv','all-33-best-known-results.csv']:
        data=u.read_csv(AREA/name);assert len(data)==33
        for row,item in zip(data,items()):
            path=args.inputs/item['relative_path'];assert u.digest(path)==row['input_sha256']==item['sha256']
            solution=Path(row['archived_solution']);assert u.digest(solution)==row['solution_sha256']
            p=u.parse(path);raw,score=u.checked(p,path,solution,u.judge_for(item,args))
            assert (raw,score)==(int(row['raw_objective']),int(row['score']))
            for judge in [args.wide_judge,args.exact_judge]:assert u.old.judge(path,solution,judge)==score
            normalized='';normalized_status='';normalized_judge='';normalized_score=''
            if row['judge_mode']=='EXTENDED_WIDE_JUDGE':
                derived=Path('tmp/ultimate-v2-normalized')/path.name
                assert u.exact_normalize(p,derived)
                assert u.checked(u.parse(derived),derived,solution,u.judge_for(item,args))==(raw,score)
                normalized=u.digest(derived)
                normalized_status='VALID_OFFICIAL_FORMAT_DERIVED'
                normalized_judge='OFFICIAL_FORMAT_WIDE_JUDGE' if item['judge_integer_safe']=='False' else 'ORIGINAL_OFFICIAL'
                normalized_score=score
            rows.append(dict(evaluation=name,instance=row['instance'],score=score,raw_objective=raw,
                             judge_mode=row['judge_mode'],normalized_input_sha256=normalized,
                             normalized_status=normalized_status,normalized_judge=normalized_judge,normalized_score=normalized_score,
                             input_sha256=item['sha256'],solution_sha256=row['solution_sha256'],validation='PASS'))
            u.write_csv(AREA/'independent-validation.csv',rows)
    integrity=json.loads((AREA/'historical-integrity.json').read_text())
    for name,digest in integrity['files'].items():assert u.digest(name)==digest,'Historical artifact changed: '+name
    if args.judge_source:assert u.digest(args.judge_source)==integrity['original_judge']['sha256']
    manifest=dict(completed_rows=len(rows),source_sha256=u.source_hash(),judges={str(p):u.digest(p) for p in [args.judge,args.wide_judge,args.exact_judge]},
                  judge_sources={p.name:u.digest(p) for p in [Path('experiments/promo-benchmark/judgeHashCode2017-wide.cpp'),V1/'judge-exact.cpp']},
                  original_judge_source=integrity['original_judge'],historical_files_unchanged=len(integrity['files']))
    (AREA/'validation-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Independently validated 66/66 final outputs',flush=True)


def diagnostics(args):
    config=frozen();local=argparse.Namespace(**vars(args));local.inputs=Path('.')
    paths=[Path('experiments/competitive-instance/competitive_streaming_videos.in'),Path('experiments/competitive-instance-v2/best-v2.in')]
    synthetic=sorted(Path('tmp/experiments').glob('*seed_29.in'))
    if not synthetic:
        synthetic=sorted(Path('experiments/competitive-instance/candidates').glob('*seed_29.in'))
    paths += synthetic[:2]
    rows=[]
    for path in paths:
        if not path.exists():continue
        item=dict(filename=path.name,relative_path=path.as_posix(),sha256=u.digest(path),judge_integer_safe='True')
        for mode,extra in [('ultimate',[]),('ultimate-v2',['--policy',config['policy'],*config['extra']])]:
            rows.append(flat(run_case(local,item,mode,10,'diagnostic-'+mode,extra)))
        u.write_csv(AREA/'diagnostic-results.csv',rows)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('action', choices=['diagnosis','pilot','compare_v1','references','snapshot_test','index_test','heldout','diagnostics','snapshot','build_version','freeze','final','export','validate','summary'])
    parser.add_argument('--inputs',type=Path,default=Path('tmp/promo-inputs'))
    parser.add_argument('--judge',type=Path,default=Path('tmp/judgeHashCode2017.exe'))
    parser.add_argument('--wide-judge',type=Path,default=Path('tmp/judgeHashCode2017-wide.exe'))
    parser.add_argument('--exact-judge',type=Path,default=Path('tmp/judge-exact.exe'))
    parser.add_argument('--judge-source',type=Path)
    parser.add_argument('--names',default='')
    parser.add_argument('--budgets',default='10')
    parser.add_argument('--policies',default='fixed,adaptive,intensive')
    parser.add_argument('--repeats',type=int,default=1)
    parser.add_argument('--label',default='screen')
    parser.add_argument('--version')
    parser.add_argument('--evaluation',choices=['fresh','incumbent'],default='fresh')
    parser.add_argument('--extra',nargs='*',default=[])
    args=parser.parse_args()
    AREA.mkdir(parents=True,exist_ok=True)
    globals()[args.action](args)


if __name__=='__main__':main()
