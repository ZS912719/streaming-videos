package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.*;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.*;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Experiment-only composition of existing solvers, with validated stage checkpoints. */
public final class PromotionPipelineRunner {
    private final ProblemInstance problem;
    private final Path directory;
    private Solution solution;
    private long previousRaw = -1;
    private int stage;

    private PromotionPipelineRunner(Path input, Path directory) throws Exception {
        this.directory = directory;
        Files.createDirectories(directory);
        long start = System.nanoTime();
        problem = new InputParser().parse(input);
        Files.writeString(directory.resolve("parse-ms.txt"), ms(System.nanoTime()-start));
        Files.writeString(directory.resolve("stages.csv"),
                "stage,operation,internal_score,raw_objective,algorithm_ms,passes,accepted,termination,solution\n");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 10) throw new IllegalArgumentException(
                "Usage: PromotionPipelineRunner input directory constructor weight lsPasses repackPasses K exchangeRounds cycles cacheOrder");
        new PromotionPipelineRunner(Path.of(args[0]), Path.of(args[1])).run(
                args[2], Double.parseDouble(args[3]), Integer.parseInt(args[4]),
                Integer.parseInt(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]),
                Integer.parseInt(args[8]), LocalSearchSolver.CacheOrder.valueOf(args[9].toUpperCase(Locale.ROOT)));
    }

    private void run(String constructor, double weight, int lsPasses, int repackPasses,
            int candidates, int exchangeRounds, int cycles, LocalSearchSolver.CacheOrder order) throws Exception {
        if ((!List.of("dynamic", "static", "greedy", "legacy-static", "baseline").contains(constructor)
                && !constructor.startsWith("load:")) || lsPasses < 0 || repackPasses < 0
                || candidates < 1 || exchangeRounds < 0 || cycles < 1)
            throw new IllegalArgumentException("Invalid pipeline configuration");
        long start = System.nanoTime();
        if (constructor.equals("greedy")) solution = new GreedySolver().solve(problem);
        else if (constructor.equals("legacy-static")) solution = new OpportunityCostGreedySolver().solve(problem);
        else if (constructor.equals("baseline")) solution = new BaselineSolver().solve(problem);
        else if (constructor.startsWith("load:")) solution = new SolutionReader().read(Path.of(constructor.substring(5)), problem);
        else solution = new DynamicOcagSolver().solve(problem, weight, false, constructor.equals("static")).solution();
        checkpoint("construction", System.nanoTime()-start, 0, 0, "completed");
        for (int cycle = 0; cycle < cycles; cycle++) {
            long before = previousRaw;
            local(lsPasses, order);
            if (repackPasses > 0) {
                // Conservative deterministic work bound, not a change to the repacking heuristic.
                long cells = 0;
                for (int c = 0; c < problem.cacheCount(); c++) {
                    cells += (long) (solution.videosInCache(c).size()+candidates)
                            * problem.cacheCapacity() * repackPasses;
                }
                if (cells <= 3_000_000_000L) {
                    CacheRepackSolver.Result result = new CacheRepackSolver().improve(
                            problem, solution, repackPasses, candidates, false);
                    checkpoint("repack", result.totalNanos(), result.passes(), result.trace().size(), result.termination());
                } else checkpoint("repack-skipped", 0, 0, 0, "estimated_dp_cells_exceed_3000000000");
            }
            if (exchangeRounds > 0) {
                long slots = 0;
                for (int c = 0; c < problem.cacheCount(); c++) slots += solution.videosInCache(c).size();
                if (slots * problem.cacheCount() <= 50_000_000L) {
                    IndexedExchangeSolver.Result result = new IndexedExchangeSolver().improve(problem, solution,
                            new IndexedExchangeSolver.Settings(1000, 64, exchangeRounds, false, 0, false));
                    checkpoint("exchange", result.totalNanos(), result.rounds(), result.accepted(), result.termination());
                } else checkpoint("exchange-skipped", 0, 0, 0, "estimated_transfer_slots_exceed_50000000");
            }
            local(lsPasses, order);
            if (previousRaw == before) break;
        }
        Files.writeString(directory.resolve("complete.txt"), "completed\n", StandardCharsets.UTF_8);
    }

    private void local(int passes, LocalSearchSolver.CacheOrder order) throws Exception {
        if (passes == 0) return;
        LocalSearchSolver.SearchStatistics stats = new LocalSearchSolver().improveWithStatistics(problem, solution,
                LocalSearchSolver.Neighborhood.COMPLETE, new LocalSearchSolver.Settings(passes, 1,
                        LocalSearchSolver.Selection.BEST, order, LocalSearchSolver.CandidateOrder.FORWARD)).statistics();
        checkpoint("local", stats.elapsedNanos(), stats.passes(), stats.acceptedMoves(), stats.termination());
    }

    private void checkpoint(String operation, long nanos, int passes, int accepted, String termination) throws Exception {
        new SolutionValidator().validate(problem, solution);
        long raw = FinalOptimizationRunner.rawObjective(problem, solution);
        long internal = new ScoreCalculator().calculate(problem, solution);
        if (raw < previousRaw || internal != raw*1000L/problem.totalRequestCount())
            throw new IllegalStateException("Nonmonotone checkpoint or score mismatch");
        previousRaw = raw;
        String filename = String.format(Locale.ROOT, "%02d-%s.txt", stage, operation);
        new SolutionWriter().write(directory.resolve(filename), problem, solution);
        String line = String.join(",", ""+stage++, operation, ""+internal, ""+raw,
                ms(nanos), ""+passes, ""+accepted, termination, filename)+"\n";
        Files.writeString(directory.resolve("stages.csv"), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.print(line);
        System.out.flush();
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos/1_000_000.0); }
}
