package com.hashcode.streaming;

import com.hashcode.streaming.io.*;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.GeneralSolver;
import java.nio.file.*;
import java.util.*;

/** Separate general-purpose CLI. Historical two/three-argument modes keep their behavior. */
public final class GeneralMain {
    private GeneralMain() { }
    public static void main(String[] args) throws Exception {
        if(args.length<3)throw new IllegalArgumentException(
                "Usage: Main ultimate|adaptive|portfolio|competition input output [--seconds N] [--seed N] [--incumbent solution] [--trace file] [--metadata file]");
        String mode=args[0].equals("ultimate")?"adaptive":args[0];
        Map<String,String> options=new HashMap<>();
        Set<String> flags=new HashSet<>();
        for(int i=3;i<args.length;i++) {
            if(Set.of("--verify","--no-repack","--no-exchange","--no-diversify").contains(args[i]))flags.add(args[i]);
            else if(Set.of("--seconds","--seed","--incumbent","--trace","--metadata","--starts","--max-units").contains(args[i])&&i+1<args.length)
                options.put(args[i],args[++i]);
            else throw new IllegalArgumentException("Unknown/incomplete option: "+args[i]);
        }
        double seconds=Double.parseDouble(options.getOrDefault("--seconds",mode.equals("competition")?"30":"10"));
        if(!Double.isFinite(seconds)||seconds<=0||seconds>86400)throw new IllegalArgumentException("Positive finite seconds <=86400 required");
        GeneralSolver.Settings settings=new GeneralSolver.Settings(GeneralSolver.Mode.valueOf(mode.toUpperCase(Locale.ROOT)),
                Math.max(1,Math.round(seconds*1000)),Long.parseLong(options.getOrDefault("--seed","20170317")),
                !flags.contains("--no-repack"),!flags.contains("--no-exchange"),
                mode.equals("competition")&&!flags.contains("--no-diversify"),options.getOrDefault("--starts","all"),
                flags.contains("--verify"),Integer.parseInt(options.getOrDefault("--max-units","2147483647")));
        long before=System.nanoTime();
        ProblemInstance p=new CheckedInputParser().parse(Path.of(args[1]));
        long parsing=System.nanoTime()-before;
        Solution incumbent=options.containsKey("--incumbent")?new SolutionReader().read(Path.of(options.get("--incumbent")),p):null;
        GeneralSolver.Result result=new GeneralSolver().solve(p,settings,incumbent);
        new SolutionValidator().validate(p,result.solution());
        Path output=Path.of(args[2]);
        if(output.getParent()!=null)Files.createDirectories(output.getParent());
        new SolutionWriter().write(output,p,result.solution());
        long score=GeneralSolver.score(p,result.raw());
        String metadata=String.format(Locale.ROOT,
                "{\n  \"mode\": \"%s\", \"budget_ms\": %d, \"seed\": %d, \"score\": %d, \"raw_objective\": %d,\n"
                +"  \"parse_ms\": %.3f, \"algorithm_ms\": %.3f, \"construction_ms\": %.3f, \"search_ms\": %.3f,\n"
                +"  \"distinct_starts\": %d, \"duplicates\": %d, \"accepted\": %d, \"units\": %d, \"termination\": \"%s\", \"incumbent\": %s\n}\n",
                mode,settings.budgetMillis(),settings.seed(),score,result.raw(),parsing/1e6,result.elapsedNanos()/1e6,
                result.constructionNanos()/1e6,(result.elapsedNanos()-result.constructionNanos())/1e6,
                result.distinctStarts(),result.duplicates(),result.accepted(),result.trace().stream().mapToInt(GeneralSolver.Event::unit).max().orElse(0),
                result.termination(),incumbent!=null);
        if(options.containsKey("--metadata"))Files.writeString(Path.of(options.get("--metadata")),metadata);
        if(options.containsKey("--trace")) {
            StringBuilder csv=new StringBuilder("unit,start,operation,delta,best_raw,elapsed_ms,accepted\n");
            for(GeneralSolver.Event event:result.trace())csv.append(String.format(Locale.ROOT,"%d,%s,%s,%d,%d,%.3f,%d%n",
                    event.unit(),event.start(),event.operation(),event.delta(),event.bestRaw(),event.elapsedNanos()/1e6,event.accepted()));
            Files.writeString(Path.of(options.get("--trace")),csv.toString());
        }
        System.out.print(metadata);
    }
}
