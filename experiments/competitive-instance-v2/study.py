#!/usr/bin/env python3
"""Bounded adversarial instance design using existing generators and solvers."""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
AREA = Path("experiments/competitive-instance-v2")


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT/file)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


bench = load("v2_bench", "promo-benchmark.py")
generator = load("v2_generator", "generate-experiments.py")
inventory = load("v2_inventory", "inventory-promotion.py")


def read_input(path):
    values = iter(map(int, Path(path).read_text(encoding="ascii").split()))
    v,e,r,c,x = [next(values) for _ in range(5)]
    sizes = [next(values) for _ in range(v)]
    endpoints = []
    for _ in range(e):
        dc,k = next(values),next(values)
        endpoints.append((dc,[(next(values),next(values)) for _ in range(k)]))
    requests = [(next(values),next(values),next(values)) for _ in range(r)]
    return dict(v=v,c=c,x=x,sizes=sizes,endpoints=endpoints,requests=requests)


def write_input(path, p):
    path.parent.mkdir(parents=True,exist_ok=True)
    with io.StringIO(newline="\n") as stream:
        stream.write(f"{p['v']} {len(p['endpoints'])} {len(p['requests'])} {p['c']} {p['x']}\n")
        stream.write(" ".join(map(str,p["sizes"]))+"\n")
        for dc,links in p["endpoints"]:
            stream.write(f"{dc} {len(links)}\n")
            for c,l in links: stream.write(f"{c} {l}\n")
        for v,e,n in p["requests"]: stream.write(f"{v} {e} {n}\n")
        content = stream.getvalue().encode("ascii")
    if path.exists() and path.read_bytes()!=content:
        raise ValueError("Refusing to overwrite a different archived candidate: "+str(path))
    if not path.exists(): path.write_bytes(content)


def placement(path, caches):
    lines = Path(path).read_text().splitlines()
    result = [set() for _ in range(caches)]
    for line in lines[1:]:
        values = list(map(int,line.split()))
        result[values[0]].update(values[1:])
    return result


def video_objectives(p, solution):
    result = [0]*p["v"]
    for v,e,n in p["requests"]:
        dc,links = p["endpoints"][e]
        latency = min([dc]+[l for c,l in links if v in solution[c]])
        result[v] += n*(dc-latency)
    return result


def diagnose(args):
    base = Path("experiments/competitive-instance")
    p = read_input(base/"competitive_streaming_videos.in")
    methods = {r["algorithm"]:r for r in bench.read_csv(base/"competitive-instance-results.csv")}
    own = placement(base/"competitive_streaming_videos.out",p["c"])
    reference = placement(methods["static-advanced"]["solution"],p["c"])
    a,b = video_objectives(p,own),video_objectives(p,reference)
    groups = [dict(group=g,raw_advantage=sum(a[v]-b[v] for v in range(g*24,(g+1)*24)),
                   differing_copies=sum(len(own[c]^reference[c]) for c in range(3*g,3*g+3)))
              for g in range(p["c"]//3)]
    videos = [dict(video=v,size=p["sizes"][v],group=v//24,raw_advantage=a[v]-b[v],
                   our_caches=" ".join(str(c) for c in range(p["c"]) if v in own[c]),
                   static_caches=" ".join(str(c) for c in range(p["c"]) if v in reference[c]))
              for v in range(p["v"]) if a[v] != b[v]]
    bench.write_csv(AREA/"v1-group-diagnostics.csv",sorted(groups,key=lambda r:-r["raw_advantage"]))
    bench.write_csv(AREA/"v1-video-diagnostics.csv",sorted(videos,key=lambda r:-r["raw_advantage"]))
    print("V1 group differences",json.dumps(sorted(groups,key=lambda r:-r["raw_advantage"])),flush=True)
    print("Largest video differences",json.dumps(sorted(videos,key=lambda r:-r["raw_advantage"])[:8]),flush=True)
    # Reuse the existing detailed constructor trace runner, not a new tracing framework.
    for mode,weight in (("dynamic",1),("static",1),("zero",0)):
        stem = AREA/"diagnostics"/mode
        metrics = stem.with_suffix(".metrics.csv")
        if metrics.exists(): continue
        command = ["java","-Xmx4g","-cp","out","com.hashcode.streaming.experiments.FinalOptimizationRunner",
                   "dynamic",str(base/"competitive_streaming_videos.in"),"empty",str(stem.with_suffix(".txt")),
                   str(metrics),str(stem.with_suffix(".trace.csv")),str(weight),"static" if mode=="static" else "dynamic"]
        subprocess.run(command,check=True,capture_output=True,text=True,timeout=30)
        score = bench.judge(base/"competitive_streaming_videos.in",stem.with_suffix(".txt"),args.judge)
        if score != int(bench.read_csv(metrics)[0]["internal_score"]): raise ValueError("Diagnostic judge mismatch")
    traces = {m:bench.read_csv(AREA/"diagnostics"/f"{m}.trace.csv") for m in ("dynamic","static","zero")}
    d,s = traces["dynamic"],traces["static"]
    first = next(i for i,(a,b) in enumerate(zip(d,s)) if (a["cache_a"],a["video_a"]) != (b["cache_a"],b["video_a"]))
    summary = dict(first_dynamic_static_divergence_step=first+1,
                   dynamic_first=d[first],static_first=s[first],
                   differing_final_videos=len(videos),positive_groups=sum(g["raw_advantage"]>0 for g in groups),
                   negative_groups=sum(g["raw_advantage"]<0 for g in groups),
                   raw_advantage=sum(a)-sum(b),
                   dynamic_positive_regret_placements=sum(int(r["regret"])>0 for r in d))
    prefix = [set() for _ in range(p["c"])]
    for point in d[:first]: prefix[int(point["cache_a"])].add(int(point["video_a"]))
    used = [sum(p["sizes"][v] for v in cache) for cache in prefix]

    def gains(video, current):
        result = [0]*p["c"]
        for v,e,n in p["requests"]:
            if v!=video: continue
            dc,links = p["endpoints"][e]
            best = min([dc]+[l for c,l in links if v in current[c]])
            for c,l in links: result[c] += n*max(0,best-l)
        return result

    choices = []
    for label,point in (("dynamic",d[first]),("static",s[first])):
        v,c = int(point["video_a"]),int(point["cache_a"])
        initial,current = gains(v,[set() for _ in prefix]),gains(v,prefix)
        initial_alt = max((g for i,g in enumerate(initial) if i!=c),default=0)
        feasible_alt = max((g for i,g in enumerate(current) if i!=c and v not in prefix[i]
                            and used[i]+p["sizes"][v]<=p["x"]),default=0)
        choices.append(dict(method=label,cache=c,video=v,size=p["sizes"][v],current_gain=current[c],
                            initial_gain=initial[c],already_stored_in=[i for i,stored in enumerate(prefix) if v in stored],
                            current_alternative_ignoring_capacity=max(g for i,g in enumerate(current) if i!=c),
                            initial_regret=max(0,initial[c]-initial_alt),current_feasible_alternative=feasible_alt,
                            current_regret=max(0,current[c]-feasible_alt)))
    summary["first_divergence_priority_diagnostics"] = choices
    (AREA/"v1-diagnostics.json").write_text(json.dumps(summary,indent=2)+"\n")
    print("Trace summary",json.dumps(summary),flush=True)


def generate_candidate(spec):
    path = AREA/"candidates"/(spec["name"]+".in")
    base = AREA/"candidates"/(spec["name"]+".base.in")
    # Reuse the existing generator, then apply an explicitly recorded transformation.
    generator.generate_competitive(base,spec.get("base_family","mixed"),spec.get("seed",17),16)
    p = read_input(base)
    base.unlink()  # Regenerable intermediate, never an existing benchmark input.
    kind = spec["family"]
    if kind == "focus":
        selected = {2,5,8,9}  # Frozen from the V1 diagnostic, explicitly selection-biased.
        p["requests"] = [(v,e,max(1,round(n*(1 if v//24 in selected else spec["background"]))))
                         for v,e,n in p["requests"]]
    elif kind == "pressure":
        p["x"] = spec["capacity"]
    elif kind == "coupling":
        rng = generator.domain_rng(spec.get("seed",17),"v2-coupling")
        for e,(dc,links) in enumerate(p["endpoints"]):
            if e%12 in (6,7,10,11):
                group = e//12
                other = (group+1)%16
                additions = [(3*other+i,rng.randint(160,450)) for i in range(spec["degree"])]
                p["endpoints"][e] = dc,links+additions
                for offset in rng.sample(range(24),8):
                    p["requests"].append((other*24+offset,e,rng.randint(500,4500)))
    # Optional independently seeded local demand noise, used only after finalist freeze.
    if "noise_seed" in spec:
        rng = generator.domain_rng(spec["noise_seed"],"v2-demand-noise")
        p["requests"] = [(v,e,min(10000,max(1,round(n*rng.uniform(0.98,1.02))))) for v,e,n in p["requests"]]
    write_input(path,p)
    features,errors = inventory.inspect_input(path)
    if errors or not features["judge_integer_safe"]: raise ValueError(f"Invalid candidate {spec}: {errors}")
    return path,features


def shared():
    return json.loads(Path("experiments/promo-benchmark/configuration.json").read_text())["parameters"]


def variants():
    return ([dict(name=f"focus-{b}",family="focus",background=b,seed=17) for b in (0.10,0.25,0.50,0.75)]
            + [dict(name=f"pressure-{x}",family="pressure",capacity=x,seed=17) for x in (180,210,270,300)]
            + [dict(name=f"coupling-{k}"+("-seed29" if seed==29 else ""),family="coupling",degree=k,seed=seed)
               for k,seed in ((1,17),(2,17),(3,17),(2,29))]
            + [dict(name=f"scarcity-{s}",family="scarcity",base_family="scarcity",seed=s) for s in (17,29,43,71)])


def configs(deep=False):
    s = shared()
    result = {"ours":s,"static":{**s,"constructor":"static"},"zero":{**s,"weight":0},
              "greedy":{**s,"constructor":"greedy"}}
    if deep:
        strong = dict(ls_passes=50,repack_passes=20,candidates=384,exchange_rounds=50,cycles=3)
        result.update({"static-reverse":{**s,"constructor":"static","cache_order":"reverse","cycles":2},
                       "zero-reverse":{**s,"weight":0,"cache_order":"reverse","cycles":2},
                       "static-deep":{**s,"constructor":"static",**strong},
                       "zero-deep":{**s,"weight":0,**strong},"greedy-deep":{**s,"constructor":"greedy",**strong}})
    return result


def run(path,label,config,args,area):
    result = bench.experiment(path,label,config,args.judge,30,area)
    if result["status"] != "completed": raise ValueError("Incomplete experiment: "+str(path))
    if Path(result["command"][5]).resolve()!=path.resolve():
        # Normalize equal-content input aliases without changing any measurement.
        assert result["input_sha256"]==bench.digest(path)
        result["instance"],result["input_path"] = path.name,path.as_posix()
        result["command"][5] = str(path)
        signature = hashlib.sha256(json.dumps(config,sort_keys=True).encode()).hexdigest()[:12]
        saved = Path(area)/"runs"/f"{result['input_sha256'][:12]}-{label}-{signature}.json"
        saved.write_text(json.dumps(result,indent=2)+"\n")
    return dict(candidate=path.stem,algorithm=label,official_score=result["official_score"],
                raw_objective=result["raw_objective"],algorithm_ms=result["algorithm_ms"],
                construction_score=result["stages"][0]["official_score"],construction_ms=result["construction_ms"],
                search_ms=result["search_ms"],solution=result["solution"],solution_sha256=result["solution_sha256"],
                input_sha256=result["input_sha256"])


def screen(args):
    specifications = variants()
    (AREA/"search-protocol.json").write_text(json.dumps(dict(candidates=specifications,
            full_candidate_limit=4,counterattack_limit=2,screening="construction only; retain construction diversity, not just initial wins",
            shared=shared(),strong=configs(True),safety_seconds=30),indent=2)+"\n")
    rows = []
    for spec in specifications:
        path,features = generate_candidate(spec)
        outputs = []
        for label,settings in list(configs().items())[:3]:
            result = run(path,label,{**settings,"ls_passes":0,"repack_passes":0,"exchange_rounds":0},args,AREA/"screen")
            rows.append({**result,"family":spec["family"],"capacity":features["capacity"],
                         "capacity_ratio":features["cache_capacity_ratio"],"average_degree":features["average_degree"]})
            outputs.append(placement(result["solution"],features["caches"]))
        disagreement = sum(len(a^b) for a,b in zip(outputs[0],outputs[1]))
        for row in rows[-3:]: row["dynamic_static_copy_disagreement"] = disagreement
        bench.write_csv(AREA/"screening.csv",rows)


def full(args):
    specs = {s["name"]:s for s in variants()}
    selected = json.loads((AREA/"finalists.json").read_text())
    if len(selected)>4: raise ValueError("Full evaluation budget exceeded")
    rows = []
    for name in selected:
        path,_ = generate_candidate(specs[name])
        for label,settings in configs().items():
            rows.append(run(path,label,settings,args,AREA/"full"))
            bench.write_csv(AREA/"full-results.csv",rows)
    gaps = []
    for name in selected:
        group = [r for r in rows if r["candidate"]==name]
        ours = next(r["official_score"] for r in group if r["algorithm"]=="ours")
        best = max(r["official_score"] for r in group if r["algorithm"]!="ours")
        gaps.append(dict(candidate=name,our_score=ours,reference_score=best,gap=ours-best,relative_gap=(ours-best)/best))
    bench.write_csv(AREA/"candidate-comparison.csv",gaps)
    winners = [r["candidate"] for r in sorted(gaps,key=lambda r:-r["relative_gap"])[:2]]
    (AREA/"counterattack-finalists.json").write_text(json.dumps(winners,indent=2)+"\n")
    print("Full comparison",json.dumps(gaps),flush=True)


def counter(args):
    selected = json.loads((AREA/"counterattack-finalists.json").read_text())
    rows = bench.read_csv(AREA/"full-results.csv")
    for name in selected:
        path = AREA/"candidates"/(name+".in")
        for label,settings in configs(True).items():
            if label in configs(): continue
            rows.append(run(path,label,settings,args,AREA/"counter"))
            bench.write_csv(AREA/"reference-comparison.csv",rows)
    comparison = []
    for name in selected:
        group = [r for r in rows if r["candidate"]==name]
        own = next(r for r in group if r["algorithm"]=="ours")
        controls = [r for r in group if r["algorithm"]!="ours"]
        best = max(controls,key=lambda r:int(r["official_score"]))
        score = int(best["official_score"])
        comparison.append(dict(candidate=name,our_score=int(own["official_score"]),reference_score=score,
                               gap=int(own["official_score"])-score,
                               relative_gap_percent=100*(int(own["official_score"])-score)/score,
                               our_ms=float(own["algorithm_ms"]),best_reference=best["algorithm"],
                               best_reference_ms=float(best["algorithm_ms"]),
                               portfolio_ms=sum(float(r["algorithm_ms"]) for r in controls)))
    bench.write_csv(AREA/"counterattack-summary.csv",comparison)
    print("Counterattack",json.dumps(comparison),flush=True)


def robustness(args):
    names = json.loads((AREA/"counterattack-finalists.json").read_text())
    specs = {s["name"]:s for s in variants()}
    rows,manifest = [],[]
    for name in names:
        for noise in (101,131):
            spec = {**specs[name],"name":f"{name}-noise{noise}","noise_seed":noise}
            path,_ = generate_candidate(spec)
            manifest.append(spec)
            for label,settings in configs().items():
                rows.append(run(path,label,settings,args,AREA/"robustness"))
                bench.write_csv(AREA/"robustness-results.csv",rows)
    (AREA/"robustness-configurations.json").write_text(json.dumps(manifest,indent=2)+"\n")
    summary = []
    for name in sorted({r["candidate"] for r in rows}):
        subset = [r for r in rows if r["candidate"]==name]
        own = next(r["official_score"] for r in subset if r["algorithm"]=="ours")
        other = max(r["official_score"] for r in subset if r["algorithm"]!="ours")
        summary.append(dict(candidate=name,our_score=own,matched_reference_score=other,
                            gap=own-other,relative_gap_percent=100*(own-other)/other))
    bench.write_csv(AREA/"robustness-summary.csv",summary)
    print("Robustness",json.dumps(summary),flush=True)


def finish(args):
    comparison = bench.read_csv(AREA/"counterattack-summary.csv")
    robustness_rows = bench.read_csv(AREA/"robustness-summary.csv")
    best = max(comparison,key=lambda r:float(r["relative_gap_percent"]))
    probe = max(robustness_rows,key=lambda r:float(r["relative_gap_percent"]))
    if float(probe["relative_gap_percent"])>float(best["relative_gap_percent"]):
        name = probe["candidate"]
        selected_spec = next(s for s in json.loads((AREA/"robustness-configurations.json").read_text()) if s["name"]==name)
        # This is explicitly post-validation selection, NOT a new held-out claim.
        group = [r for r in bench.read_csv(AREA/"robustness-results.csv") if r["candidate"]==name]
        for label,settings in configs(True).items():
            if label in configs(): continue
            group.append(run(AREA/"candidates"/(name+".in"),label,settings,args,AREA/"counter-noise"))
        bench.write_csv(AREA/"noise-counterattack.csv",group)
        own = next(r for r in group if r["algorithm"]=="ours")
        controls = [r for r in group if r["algorithm"]!="ours"]
        opponent = max(controls,key=lambda r:int(r["official_score"]))
        score = int(opponent["official_score"])
        challenger = dict(candidate=name,our_score=int(own["official_score"]),reference_score=score,
                          gap=int(own["official_score"])-score,
                          relative_gap_percent=100*(int(own["official_score"])-score)/score,
                          our_ms=float(own["algorithm_ms"]),best_reference=opponent["algorithm"],
                          best_reference_ms=float(opponent["algorithm_ms"]),
                          portfolio_ms=sum(float(r["algorithm_ms"]) for r in controls))
        comparison.append(challenger)
        bench.write_csv(AREA/"final-comparison.csv",comparison)
        best = max(comparison,key=lambda r:float(r["relative_gap_percent"]))
    name = best["candidate"]
    all_specs = variants()+json.loads((AREA/"robustness-configurations.json").read_text())
    selected_spec = next(s for s in all_specs if s["name"]==name)
    source = AREA/"candidates"/(name+".in")
    # Fresh repetitions are isolated from discovery measurements and rejudge all stages.
    repetitions = []
    for rep in (1,2,3):
        for label in ("ours",best["best_reference"]):
            repetitions.append({**run(source,label+f"-rep{rep}",configs(True)[label],args,AREA/"repeats"),
                                "repetition":rep,"method":label})
    bench.write_csv(AREA/"timing-repetitions.csv",repetitions)
    all_results = bench.read_csv(AREA/"reference-comparison.csv")
    if (AREA/"noise-counterattack.csv").exists(): all_results += bench.read_csv(AREA/"noise-counterattack.csv")
    own = next(r for r in all_results if r["candidate"]==name and r["algorithm"]=="ours")
    target = AREA/"best-v2.in"
    shutil.copyfile(source,target)
    shutil.copyfile(own["solution"],AREA/"best-v2.out")
    assert bench.judge(target,AREA/"best-v2.out",args.judge)==int(own["official_score"])
    import statistics
    timings = {method:statistics.median(float(r["algorithm_ms"]) for r in repetitions if r["method"]==method)
               for method in {r["method"] for r in repetitions}}
    outcome = dict(best_v2=selected_spec,input_sha256=bench.digest(target),output_sha256=bench.digest(AREA/"best-v2.out"),
                   official_score=int(own["official_score"]),strongest_reference_score=int(best["reference_score"]),
                   gap=int(best["gap"]),relative_gap_percent=float(best["relative_gap_percent"]),median_ms=timings,
                   v1_gap_percent=100*2780/977913,
                   recommendation="V2" if float(best["relative_gap_percent"])>100*2780/977913 else "V1",
                   shared=shared(),reference_configurations=configs(True),
                   tested_candidates=variants(),finalists=json.loads((AREA/"finalists.json").read_text()),
                   counterattack_finalists=json.loads((AREA/"counterattack-finalists.json").read_text()),
                   robustness="Independent +/-2% request-count noise with seeds 101 and 131; same underlying V1 topology",
                   selection_note="Best local noise variant selected after inspecting robustness results, then independently counterattacked; not held-out evidence",
                   judge="unchanged supplied judgeHashCode2017.cpp; safe request totals and products")
    (AREA/"selected-configuration.json").write_text(json.dumps(outcome,indent=2)+"\n")
    selected_rows = [r for r in all_results if r["candidate"]==name]
    reference_table = []
    for row in selected_rows:
        label = row["algorithm"]
        score = bench.judge(target,row["solution"],args.judge)
        if score != int(row["official_score"]): raise ValueError("Final reference judge mismatch")
        reference_table.append(dict(algorithm=label,official_score=score,
                                    algorithm_ms=timings.get(label,float(row["algorithm_ms"])),
                                    timing="median of 3" if label in timings else "single run",
                                    budget="matched repair" if label in configs() else "increased repair",
                                    solution=row["solution"]))
    winning_ref = next(r for r in reference_table if r["algorithm"]==best["best_reference"])
    reference_table.append(dict(algorithm="eight-start-portfolio",official_score=best["reference_score"],
                                algorithm_ms=best["portfolio_ms"],timing="sum of 8 measured components",
                                budget="increased total work",solution=winning_ref["solution"]))
    bench.write_csv(AREA/"strong-reference-comparison.csv",reference_table)
    print("Final result",json.dumps({k:outcome[k] for k in ("best_v2","official_score","strongest_reference_score",
                                "gap","relative_gap_percent","median_ms","recommendation")}),flush=True)


def replay(args):
    outcome = json.loads((AREA/"selected-configuration.json").read_text())
    path,features = generate_candidate(outcome["best_v2"])
    if bench.digest(path)!=outcome["input_sha256"]: raise ValueError("Generator hash mismatch")
    rows = []
    import time
    temporary = Path("tmp/competitive-v2-replay")/str(time.time_ns())
    for reference in bench.read_csv(AREA/"strong-reference-comparison.csv"):
        label = reference["algorithm"]
        if label=="eight-start-portfolio": continue
        row = run(path,label,outcome["reference_configurations"][label],args,temporary)
        if row["official_score"]!=int(reference["official_score"]) or row["solution_sha256"]!=bench.digest(reference["solution"]):
            raise ValueError("Fresh replay mismatch: "+label)
        rows.append(row)
    bench.write_csv(AREA/"fresh-replay.csv",rows)
    print(f"Fresh replay: {len(rows)} official scores and exact solution hashes matched",flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["diagnose","screen","full","counter","robustness","finish","replay"])
    parser.add_argument("--judge",type=Path,default=Path("tmp/judgeHashCode2017.exe"))
    args = parser.parse_args()
    os.chdir(ROOT)
    AREA.mkdir(parents=True,exist_ok=True)
    {"diagnose":diagnose,"screen":screen,"full":full,"counter":counter,"robustness":robustness,
     "finish":finish,"replay":replay}[args.action](args)


if __name__ == "__main__": main()
