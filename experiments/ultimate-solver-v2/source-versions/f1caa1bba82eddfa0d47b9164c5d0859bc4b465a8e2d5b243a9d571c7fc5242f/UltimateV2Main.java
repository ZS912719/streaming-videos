package com.hashcode.streaming;

import com.hashcode.streaming.io.*;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.*;
import java.nio.file.*;
import java.util.*;

/** Portable V2 entry point with separate timing and a safe final checkpoint. */
public final class UltimateV2Main {
    private UltimateV2Main() { }
    public static void main(String[] args)throws Exception {
        long entry=System.nanoTime();
        if(args.length<3)throw new IllegalArgumentException("Main ultimate-v2 input output [--seconds 180] [--policy fixed|adaptive|intensive]");
        Map<String,String> o=new HashMap<>();Set<String> flags=new HashSet<>();
        for(int i=3;i<args.length;i++) {
            if(Set.of("--verify","--no-packing","--no-deep-repack","--no-exchange","--no-complement","--pattern","--no-benefit-index","--no-size-pruning").contains(args[i]))flags.add(args[i]);
            else if(Set.of("--seconds","--seed","--incumbent","--trace","--metadata","--policy","--preset","--max-units","--checkpoints").contains(args[i])&&i+1<args.length)o.put(args[i],args[++i]);
            else throw new IllegalArgumentException("Unknown/incomplete option: "+args[i]);
        }
        String preset=o.getOrDefault("--preset","standard");
        int nominal=switch(preset){case "quick"->10;case "standard"->60;case "competition"->180;case "deep"->300;default->throw new IllegalArgumentException("Unknown preset");};
        double seconds=Double.parseDouble(o.getOrDefault("--seconds",""+nominal));
        if(!Double.isFinite(seconds)||seconds<=0||seconds>86400)throw new IllegalArgumentException("Positive finite seconds <=86400 required");
        long before=System.nanoTime();ProblemInstance p=new CheckedInputParser().parse(Path.of(args[1]));long parsing=System.nanoTime()-before;
        Solution incumbent=o.containsKey("--incumbent")?new SolutionReader().read(Path.of(o.get("--incumbent")),p):null;
        // Budget includes parsing and process uptime; final validation/output overhead is explicit.
        long uptime=(long)(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()*1_000_000L);
        long remaining=Math.max(1,Math.round(seconds*1000)-uptime/1_000_000L);
        UltimateV2Solver.Settings settings=new UltimateV2Solver.Settings(UltimateV2Solver.Policy.valueOf(o.getOrDefault("--policy","adaptive").toUpperCase(Locale.ROOT)),
                remaining,Long.parseLong(o.getOrDefault("--seed","20170317")),flags.contains("--verify"),Integer.parseInt(o.getOrDefault("--max-units","2147483647")),
                !flags.contains("--no-packing"),!flags.contains("--no-deep-repack"),!flags.contains("--no-exchange"),!flags.contains("--no-complement"),flags.contains("--pattern"),!flags.contains("--no-benefit-index"),!flags.contains("--no-size-pruning"));
        StringBuilder checkpointRows=new StringBuilder("budget_seconds,raw_objective,score,observed_ms,early_termination,solution\n");
        UltimateV2Solver.CheckpointSink sink=o.containsKey("--checkpoints")?(secondsAt,solution,raw,observed,early)-> {
            try {
                Path dir=Path.of(o.get("--checkpoints"));Files.createDirectories(dir);
                Path file=dir.resolve("checkpoint-"+secondsAt+".txt");new SolutionWriter().write(file,p,solution);
                checkpointRows.append(String.format(Locale.ROOT,"%d,%d,%d,%.3f,%s,%s%n",secondsAt,raw,GeneralSolver.score(p,raw),observed/1e6,early,file.getFileName()));
                Files.writeString(dir.resolve("checkpoints.csv"),checkpointRows.toString());
            }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
        }:null;
        before=System.nanoTime();UltimateV2Solver.Result r=new UltimateV2Solver().solve(p,settings,incumbent,sink,uptime);long algorithm=System.nanoTime()-before;
        before=System.nanoTime();new SolutionValidator().validate(p,r.solution());long validation=System.nanoTime()-before+r.validationNanos();
        before=System.nanoTime();Path output=Path.of(args[2]);if(output.getParent()!=null)Files.createDirectories(output.getParent());new SolutionWriter().write(output,p,r.solution());long writing=System.nanoTime()-before;
        String metadata=String.format(Locale.ROOT,"{\n\"mode\":\"ultimate-v2\",\"policy\":\"%s\",\"budget_ms\":%.0f,\"score\":%d,\"raw_objective\":%d,\n\"parse_ms\":%.3f,\"algorithm_ms\":%.3f,\"construction_ms\":%.3f,\"search_ms\":%.3f,\"validation_ms\":%.3f,\"output_ms\":%.3f,\"entry_elapsed_ms\":%.3f,\"engine_offset_ms\":%.3f,\n\"accepted\":%d,\"distinct_starts\":%d,\"duplicates\":%d,\"termination\":\"%s\",\"incumbent\":%s\n}\n",
                settings.policy().name().toLowerCase(Locale.ROOT),seconds*1000,GeneralSolver.score(p,r.raw()),r.raw(),parsing/1e6,algorithm/1e6,r.constructionNanos()/1e6,r.searchNanos()/1e6,validation/1e6,writing/1e6,(System.nanoTime()-entry)/1e6,uptime/1e6,r.accepted(),r.distinctStarts(),r.duplicates(),r.termination(),incumbent!=null);
        String operations=r.operationNanos().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->String.format(Locale.ROOT,"\"%s\":%.3f",e.getKey(),e.getValue()/1e6)).collect(java.util.stream.Collectors.joining(","));
        metadata=metadata.replace("\n}\n",",\n\"operation_ms\":{"+operations+"}\n}\n");
        String trajectories=r.trajectories().stream().map(t->String.format(Locale.ROOT,
                "{\"name\":\"%s\",\"initial_score\":%d,\"final_score\":%d,\"repair_ms\":%.3f,\"cycle\":%d,\"phase\":%d,\"finished\":%s}",
                t.name(),GeneralSolver.score(p,t.initialRaw()),GeneralSolver.score(p,t.raw()),t.repairNanos()/1e6,t.cycle(),t.phase(),t.finished()))
                .collect(java.util.stream.Collectors.joining(","));
        metadata=metadata.replace("\n}\n",",\n\"trajectories\":["+trajectories+"]\n}\n");
        if(o.containsKey("--metadata"))Files.writeString(Path.of(o.get("--metadata")),metadata);
        if(o.containsKey("--trace")) {
            StringBuilder csv=new StringBuilder("unit,start,operation,delta,best_raw,elapsed_ms,accepted,arm_raw\n");
            for(var event:r.trace())csv.append(String.format(Locale.ROOT,"%d,%s,%s,%d,%d,%.3f,%d,%d%n",event.unit(),event.start(),event.operation(),event.delta(),event.bestRaw(),event.elapsedNanos()/1e6,event.accepted(),event.armRaw()));
            Files.writeString(Path.of(o.get("--trace")),csv.toString());
        }
        System.out.print(metadata);
    }
}
