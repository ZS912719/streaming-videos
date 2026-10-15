package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionReader;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.LocalSearchSolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Applies a configured search to a saved, validated starting placement. */
public final class LocalSearchTuningRunner {
    private LocalSearchTuningRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 9 && args.length != 10) {
            throw new IllegalArgumentException("Usage: LocalSearchTuningRunner <input.in>"
                    + " <start.out> <result.out> <metrics.csv> <trace.csv>"
                    + " <max-passes> <max-moves-per-cache> <best|first>"
                    + " <cache-forward|reverse> [candidate-forward|reverse]");
        }
        Path inputPath = Path.of(args[0]);
        Path startPath = Path.of(args[1]);
        Path resultPath = Path.of(args[2]);
        Path metricsPath = Path.of(args[3]);
        Path tracePath = Path.of(args[4]);
        LocalSearchSolver.Settings settings = new LocalSearchSolver.Settings(
                Integer.parseInt(args[5]), Integer.parseInt(args[6]),
                LocalSearchSolver.Selection.valueOf(args[7].toUpperCase(Locale.ROOT)),
                LocalSearchSolver.CacheOrder.valueOf(args[8].toUpperCase(Locale.ROOT)),
                LocalSearchSolver.CandidateOrder.valueOf(
                        (args.length == 10 ? args[9] : "forward").toUpperCase(Locale.ROOT)));

        ProblemInstance problem = new InputParser().parse(inputPath);
        Solution solution = new SolutionReader().read(startPath, problem);
        ScoreCalculator calculator = new ScoreCalculator();
        long initialScore = calculator.calculate(problem, solution);
        long searchStart = System.nanoTime();
        LocalSearchSolver.SearchResult result = new LocalSearchSolver().improveWithStatistics(
                problem, solution, LocalSearchSolver.Neighborhood.COMPLETE, settings);
        long searchNanos = System.nanoTime() - searchStart;
        new SolutionValidator().validate(problem, result.solution());
        long finalScore = calculator.calculate(problem, result.solution());
        LocalSearchSolver.SearchStatistics stats = result.statistics();
        long exactSavedLatency = savedLatency(problem, result.solution());
        if (exactSavedLatency != stats.finalSavedLatency()) {
            throw new IllegalStateException("Incremental saved latency differs from full scan: "
                    + stats.finalSavedLatency() + " versus " + exactSavedLatency);
        }
        long expectedScore = problem.totalRequestCount() == 0 ? 0
                : stats.finalSavedLatency() * 1000L / problem.totalRequestCount();
        if (expectedScore != finalScore) {
            throw new IllegalStateException("Incremental and full scores differ: "
                    + expectedScore + " versus " + finalScore);
        }

        Files.createDirectories(resultPath.toAbsolutePath().getParent());
        Files.createDirectories(metricsPath.toAbsolutePath().getParent());
        Files.createDirectories(tracePath.toAbsolutePath().getParent());
        new SolutionWriter().write(resultPath, problem, result.solution());
        Files.write(metricsPath, List.of(
                "dataset,start_solution,max_passes,max_moves_per_cache,selection,cache_order,"
                        + "candidate_order,"
                        + "initial_score,internal_final_score,initial_saved_latency,"
                        + "final_saved_latency,passes,accepted_moves,additions,replacements,"
                        + "evaluated_moves,search_ms,termination",
                String.join(",", inputPath.getFileName().toString(),
                        startPath.getFileName().toString(),
                        Integer.toString(settings.maxPasses()),
                        Integer.toString(settings.maxMovesPerCache()),
                        settings.selection().name().toLowerCase(Locale.ROOT),
                        settings.cacheOrder().name().toLowerCase(Locale.ROOT),
                        settings.candidateOrder().name().toLowerCase(Locale.ROOT),
                        Long.toString(initialScore), Long.toString(finalScore),
                        Long.toString(stats.initialSavedLatency()),
                        Long.toString(stats.finalSavedLatency()),
                        Integer.toString(stats.passes()),
                        Integer.toString(stats.acceptedMoves()),
                        Integer.toString(stats.additions()),
                        Integer.toString(stats.replacements()),
                        Long.toString(stats.evaluatedMoves()),
                        String.format(Locale.ROOT, "%.3f", searchNanos / 1_000_000.0),
                        stats.termination())), StandardCharsets.UTF_8);

        List<String> trace = new ArrayList<>();
        trace.add("pass,accepted_move,cache_id,move_type,delta_saved_latency,"
                + "cumulative_saved_latency,score_equivalent,elapsed_ms");
        for (LocalSearchSolver.TracePoint point : stats.trace()) {
            long score = problem.totalRequestCount() == 0 ? 0
                    : point.savedLatency() * 1000L / problem.totalRequestCount();
            trace.add(String.join(",", Integer.toString(point.pass()),
                    Integer.toString(point.acceptedMove()), Integer.toString(point.cacheId()),
                    point.moveType(), Long.toString(point.improvement()),
                    Long.toString(point.savedLatency()), Long.toString(score),
                    String.format(Locale.ROOT, "%.3f", point.elapsedNanos() / 1_000_000.0)));
        }
        Files.write(tracePath, trace, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "%s -> %d; %d passes, %d moves, %.3f ms%n",
                startPath.getFileName(), finalScore, stats.passes(), stats.acceptedMoves(),
                searchNanos / 1_000_000.0);
    }

    private static long savedLatency(ProblemInstance problem, Solution solution) {
        long saved = 0L;
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand demand : endpoint.requestsByVideo().values()) {
                int best = endpoint.dataCenterLatency();
                for (CacheConnection connection : endpoint.connections()) {
                    if (solution.contains(connection.cacheId(), demand.videoId())) {
                        best = Math.min(best, connection.latency());
                    }
                }
                saved += demand.requestCount()
                        * (long) (endpoint.dataCenterLatency() - best);
            }
        }
        return saved;
    }
}
