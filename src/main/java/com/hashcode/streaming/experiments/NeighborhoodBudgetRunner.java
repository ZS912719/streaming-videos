package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionReader;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solver.GreedySolver;
import com.hashcode.streaming.solver.LocalSearchSolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Cold-JVM, equal repair-time comparison from an identical serialized greedy seed. */
public final class NeighborhoodBudgetRunner {
    private NeighborhoodBudgetRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--prepare")) {
            var problem = new InputParser().parse(Path.of(args[1]));
            long start = System.nanoTime();
            var seed = new GreedySolver().solve(problem);
            long elapsed = System.nanoTime() - start;
            new SolutionValidator().validate(problem, seed);
            new SolutionWriter().write(Path.of(args[2]), problem, seed);
            Files.writeString(Path.of(args[3]), "score,construction_ms\n"
                    + new ScoreCalculator().calculate(problem, seed) + "," + ms(elapsed) + "\n");
            return;
        }
        if (args.length != 7 || !(args[0].equals("one") || args[0].equals("two"))) {
            throw new IllegalArgumentException("Usage: NeighborhoodBudgetRunner "
                    + "--prepare <input> <seed.out> <seed.csv> | "
                    + "<one|two> <input> <seed.out> <output> <metrics.csv> <trace.csv> <budget-ms>");
        }
        long budgetMs = Long.parseLong(args[6]);
        if (budgetMs < 0) throw new IllegalArgumentException("Negative budget");
        long budgetNanos = Math.multiplyExact(budgetMs, 1_000_000L);
        var problem = new InputParser().parse(Path.of(args[1]));
        var seed = new SolutionReader().read(Path.of(args[2]), problem);
        var scorer = new ScoreCalculator();
        long initialScore = scorer.calculate(problem, seed);
        var neighborhood = args[0].equals("one") ? LocalSearchSolver.Neighborhood.COMPLETE
                : LocalSearchSolver.Neighborhood.TWO_REMOVALS;
        long start = System.nanoTime();
        var result = new LocalSearchSolver().improveWithinBudget(problem, seed, neighborhood, budgetNanos);
        long elapsed = System.nanoTime() - start;
        new SolutionValidator().validate(problem, result.solution());
        long score = scorer.calculate(problem, result.solution());
        var stats = result.statistics();
        long tracedScore = problem.totalRequestCount() == 0 ? 0
                : stats.finalSavedLatency() * 1000L / problem.totalRequestCount();
        if (score != tracedScore || score < initialScore
                || stats.acceptedMoves() != stats.additions() + stats.replacements()) {
            throw new IllegalStateException("Search statistics disagree with independently scored output");
        }
        new SolutionWriter().write(Path.of(args[3]), problem, result.solution());
        Files.writeString(Path.of(args[4]),
                "dataset,variant,budget_ms,initial_score,score,search_ms,overrun_ms,passes,"
                + "evaluated_candidates,evaluated_removal_pairs,accepted_moves,additions,"
                + "single_replacements,double_replacements,saved_latency_gain,termination\n"
                + String.join(",", Path.of(args[1]).getFileName().toString(), args[0],
                        Long.toString(budgetMs), Long.toString(initialScore), Long.toString(score),
                        ms(elapsed), ms(Math.max(0L, elapsed - budgetNanos)),
                        Integer.toString(stats.passes()), Long.toString(stats.evaluatedMoves()),
                        Long.toString(stats.evaluatedRemovalPairs()), Integer.toString(stats.acceptedMoves()),
                        Integer.toString(stats.additions()),
                        Integer.toString(stats.replacements() - stats.doubleReplacements()),
                        Integer.toString(stats.doubleReplacements()), Long.toString(stats.totalImprovement()),
                        stats.termination()) + "\n");
        try (var writer = Files.newBufferedWriter(Path.of(args[5]))) {
            writer.write("accepted_move,pass,cache_id,move_type,added_video_id,removed_video_id,"
                    + "second_removed_video_id,saved_latency_gain,cumulative_saved_latency,score,elapsed_ms\n");
            for (var point : stats.trace()) {
                long currentScore = problem.totalRequestCount() == 0 ? 0
                        : point.savedLatency() * 1000L / problem.totalRequestCount();
                writer.write(String.join(",", Integer.toString(point.acceptedMove()),
                        Integer.toString(point.pass()), Integer.toString(point.cacheId()), point.moveType(),
                        Integer.toString(point.addedVideoId()), Integer.toString(point.removedVideoId()),
                        Integer.toString(point.secondRemovedVideoId()), Long.toString(point.improvement()),
                        Long.toString(point.savedLatency()), Long.toString(currentScore),
                        ms(point.elapsedNanos())) + "\n");
            }
        }
        System.out.printf(Locale.ROOT, "%s / %s / %d ms: score=%d, search=%s ms, accepted=%d, pairs=%d, %s%n",
                args[1], args[0], budgetMs, score, ms(elapsed), stats.acceptedMoves(),
                stats.doubleReplacements(), stats.termination());
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
