package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.*;
import com.hashcode.streaming.model.*;
import com.hashcode.streaming.scoring.*;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Phase metrics and compact traces for independent and joint advanced experiments. */
public final class FinalOptimizationRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 7) throw new IllegalArgumentException(
                "Usage: FinalOptimizationRunner <dynamic|exchange|packing|repack> <input> <start-or-empty>"
                + " <output> <metrics.csv> <trace.csv> <weight | pairs videos rounds first milliseconds | passes>");
        String mode = args[0];
        ProblemInstance p = new InputParser().parse(Path.of(args[1]));
        Path output = Path.of(args[3]), metrics = Path.of(args[4]), tracePath = Path.of(args[5]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectories(metrics.toAbsolutePath().getParent());
        Files.createDirectories(tracePath.toAbsolutePath().getParent());
        Solution solution;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("dataset", Path.of(args[1]).getFileName().toString());
        values.put("mode", mode);
        List<String> trace = new ArrayList<>();
        trace.add("step,raw_objective,elapsed_ms,cache_a,video_a,cache_b,video_b,gain,regret");
        if (mode.equals("dynamic")) {
            double weight = Double.parseDouble(args[6]);
            String regretMode = args.length > 7 ? args[7] : "dynamic";
            DynamicOcagSolver.Result result = new DynamicOcagSolver().solve(p, weight, false, regretMode.equals("static"));
            solution = result.solution();
            put(values, "weight", weight, "regret_mode", regretMode, "algorithm_ms", ms(result.totalNanos()),
                    "index_ms", ms(result.indexNanos()), "regret_updates", result.regretUpdates(),
                    "infeasible_alternatives", result.infeasibleAlternatives(),
                    "candidate_evaluations", result.candidateEvaluations(),
                    "estimated_array_bytes", result.gainArrayBytes(), "accepted", result.trace().size());
            int staleRegret = 0, positiveRegret = 0;
            for (DynamicOcagSolver.Placement point : result.trace()) {
                if (point.regret() != point.initialRegret()) staleRegret++;
                if (point.regret() > 0) positiveRegret++;
                trace.add(String.join(",", "" + point.step(), "" + point.rawObjective(),
                        ms(point.elapsedNanos()), "" + point.cache(), "" + point.video(),
                        "-1", "-1", "" + point.gain(), "" + point.regret()));
            }
            put(values, "changed_selected_regrets", staleRegret, "positive_regret_placements", positiveRegret,
                    "termination", "no_positive_feasible_candidate");
        } else if (mode.equals("exchange")) {
            solution = new SolutionReader().read(Path.of(args[2]), p);
            int pairs = Integer.parseInt(args[6]), videos = Integer.parseInt(args[7]), rounds = Integer.parseInt(args[8]);
            boolean first = Boolean.parseBoolean(args[9]);
            long milliseconds = Long.parseLong(args[10]);
            IndexedExchangeSolver.Result result = new IndexedExchangeSolver().improve(p, solution,
                    new IndexedExchangeSolver.Settings(pairs, videos, rounds, first, milliseconds, false));
            put(values, "pairs", pairs, "videos_per_cache", videos, "round_limit", rounds,
                    "first_improvement", first, "time_limit_ms", milliseconds,
                    "algorithm_ms", ms(result.totalNanos()), "index_ms", ms(result.indexNanos()),
                    "scan_ms", ms(result.scanNanos()), "update_ms", ms(result.updateNanos()),
                    "rounds", result.rounds(), "accepted", result.accepted(),
                    "candidate_evaluations", result.evaluated(), "feasible", result.feasible(),
                    "positive", result.positive(), "rounded_improvements", result.roundedImprovements(),
                    "estimated_array_bytes", result.indexArrayBytes(), "termination", result.termination());
            for (IndexedExchangeSolver.Move point : result.trace()) {
                trace.add(String.join(",", "" + point.round(), "" + point.rawObjective(),
                        ms(point.elapsedNanos()), "" + point.cacheA(), "" + point.videoA(),
                        "" + point.cacheB(), "" + point.videoB(), "" + point.delta(), "0"));
            }
        } else if (mode.equals("packing")) {
            solution = new SolutionReader().read(Path.of(args[2]), p);
            PackingSolver.Result result = new PackingSolver().improve(p, solution,
                    Integer.parseInt(args[6]), Integer.parseInt(args[7]), args[8], false);
            put(values, "algorithm_ms", ms(result.totalNanos()), "accepted", result.trace().size(),
                    "candidate_evaluations", result.evaluated(), "passes", result.passes(),
                    "candidate_limit", args[7], "neighborhood", args[8], "termination", result.termination());
            for (PackingSolver.Move point : result.trace()) {
                trace.add(String.join(",", "" + point.pass(), "" + point.rawObjective(), ms(point.elapsedNanos()),
                        "" + point.cache(), "" + point.addA(), "" + point.removeA(), "" + point.removeB(),
                        "" + point.delta(), "0"));
            }
        } else if (mode.equals("repack")) {
            solution = new SolutionReader().read(Path.of(args[2]), p);
            CacheRepackSolver.Result result = new CacheRepackSolver().improve(p, solution,
                    Integer.parseInt(args[6]), Integer.parseInt(args[7]), false);
            put(values, "algorithm_ms", ms(result.totalNanos()), "accepted", result.trace().size(),
                    "candidate_evaluations", result.evaluatedCells(), "passes", result.passes(),
                    "candidate_limit", args[7], "termination", result.termination());
            for (CacheRepackSolver.Move point : result.trace()) {
                trace.add(String.join(",", "" + point.pass(), "" + point.rawObjective(), ms(point.elapsedNanos()),
                        "" + point.cache(), "-1", "-1", "-1", "" + point.delta(), "0"));
            }
        } else throw new IllegalArgumentException("Unknown mode: " + mode);
        new SolutionValidator().validate(p, solution);
        long score = new ScoreCalculator().calculate(p, solution);
        long raw = rawObjective(p, solution);
        if (trace.size() > 1 && Long.parseLong(trace.get(trace.size() - 1).split(",")[1]) != raw) {
            throw new IllegalStateException("Trace and full objective mismatch");
        }
        put(values, "internal_score", score, "raw_objective", raw);
        new SolutionWriter().write(output, p, solution);
        Files.write(metrics, List.of(String.join(",", values.keySet()), String.join(",", values.values())), StandardCharsets.UTF_8);
        Files.write(tracePath, trace, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "%s %s score=%d algorithm_ms=%s%n", mode, args[1], score, values.get("algorithm_ms"));
    }

    private static void put(Map<String, String> values, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i].toString(), pairs[i + 1].toString());
    }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0); }
    public static long rawObjective(ProblemInstance p, Solution solution) {
        long total = 0L;
        for (Endpoint endpoint : p.endpoints()) for (RequestDemand demand : endpoint.requestsByVideo().values()) {
            int best = endpoint.dataCenterLatency();
            for (CacheConnection link : endpoint.connections()) if (solution.contains(link.cacheId(), demand.videoId())) best = Math.min(best, link.latency());
            total += demand.requestCount() * (long) (endpoint.dataCenterLatency() - best);
        }
        return total;
    }
}
