package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.BaselineSolver;
import com.hashcode.streaming.solver.GreedySolver;
import com.hashcode.streaming.solver.LocalSearchSolver;
import com.hashcode.streaming.solver.OpportunityCostGreedySolver;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Single-process, phase-timed runner used by the reproducible scientific experiments. */
public final class ScientificExperimentRunner {
    private ScientificExperimentRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            System.err.println("Usage: ScientificExperimentRunner <algorithm> <input> <output>"
                    + " <metrics.csv> [local-search-trace.csv] [ocag-trace.csv]");
            System.exit(2);
        }
        String algorithm = args[0].toLowerCase(Locale.ROOT);
        Path inputPath = Path.of(args[1]);
        Path outputPath = Path.of(args[2]);
        Path metricsPath = Path.of(args[3]);
        Path localTracePath = args.length >= 5 ? Path.of(args[4]) : null;
        Path ocagTracePath = args.length >= 6 ? Path.of(args[5]) : null;

        long endToEndStart = System.nanoTime();
        long phaseStart = System.nanoTime();
        ProblemInstance problem = new InputParser().parse(inputPath);
        long parseNanos = System.nanoTime() - phaseStart;

        phaseStart = System.nanoTime();
        Construction construction = construct(algorithm, problem);
        long constructionNanos = System.nanoTime() - phaseStart;
        ScoreCalculator calculator = new ScoreCalculator();
        long initialScore = calculator.calculate(problem, construction.solution());

        LocalSearchSolver.SearchStatistics localStatistics = null;
        Solution solution = construction.solution();
        long localSearchNanos = 0L;
        LocalSearchSolver.Neighborhood neighborhood = neighborhood(algorithm);
        if (neighborhood != null) {
            phaseStart = System.nanoTime();
            LocalSearchSolver.SearchResult result = new LocalSearchSolver()
                    .improveWithStatistics(problem, solution, neighborhood);
            localSearchNanos = System.nanoTime() - phaseStart;
            solution = result.solution();
            localStatistics = result.statistics();
        }

        phaseStart = System.nanoTime();
        new SolutionValidator().validate(problem, solution);
        long finalScore = calculator.calculate(problem, solution);
        long validationScoringNanos = System.nanoTime() - phaseStart;

        phaseStart = System.nanoTime();
        createParent(outputPath);
        new SolutionWriter().write(outputPath, problem, solution);
        long writingNanos = System.nanoTime() - phaseStart;
        long endToEndNanos = System.nanoTime() - endToEndStart;

        writeMetrics(metricsPath, inputPath.getFileName().toString(), algorithm,
                construction.initialAlgorithm(), initialScore, finalScore,
                parseNanos, constructionNanos, localSearchNanos,
                validationScoringNanos, writingNanos, endToEndNanos,
                localStatistics, construction.ocagStatistics());
        if (localTracePath != null && localStatistics != null) {
            writeLocalTrace(localTracePath, inputPath.getFileName().toString(),
                    algorithm, problem, localStatistics);
        }
        if (ocagTracePath != null && construction.ocagStatistics() != null) {
            writeOcagTrace(ocagTracePath, inputPath.getFileName().toString(),
                    algorithm, construction.ocagStatistics());
        }
        System.err.printf(Locale.ROOT,
                "Experiment: %s | Initial score: %d | Final score: %d | Algorithm time: %.3f s%n",
                algorithm, initialScore, finalScore,
                (constructionNanos + localSearchNanos) / 1_000_000_000.0);
    }

    private static Construction construct(String algorithm, ProblemInstance problem) {
        if (algorithm.equals("baseline") || algorithm.equals("baseline-ls")) {
            return new Construction(new BaselineSolver().solve(problem), "baseline", null);
        }
        if (algorithm.equals("greedy") || algorithm.startsWith("greedy-ls")) {
            return new Construction(new GreedySolver().solve(problem), "greedy", null);
        }
        if (algorithm.equals("ocag") || algorithm.equals("ocag-ls")) {
            OpportunityCostGreedySolver.ConstructionResult result =
                    new OpportunityCostGreedySolver().solveWithStatistics(problem);
            return new Construction(result.solution(), "ocag", result.statistics());
        }
        throw new IllegalArgumentException("Unknown experiment algorithm: " + algorithm);
    }

    private static LocalSearchSolver.Neighborhood neighborhood(String algorithm) {
        return switch (algorithm) {
            case "baseline-ls", "greedy-ls", "ocag-ls" ->
                    LocalSearchSolver.Neighborhood.COMPLETE;
            case "greedy-ls-insertion" -> LocalSearchSolver.Neighborhood.INSERTION_ONLY;
            case "greedy-ls-replacement" -> LocalSearchSolver.Neighborhood.REPLACEMENT_ONLY;
            default -> null;
        };
    }

    private static void writeMetrics(Path path, String dataset, String algorithm,
            String initialAlgorithm, long initialScore, long finalScore,
            long parseNanos, long constructionNanos, long localSearchNanos,
            long validationScoringNanos, long writingNanos, long endToEndNanos,
            LocalSearchSolver.SearchStatistics local,
            OpportunityCostGreedySolver.ConstructionStatistics ocag) throws Exception {
        createParent(path);
        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8)) {
            writer.write("dataset,algorithm,initial_algorithm,internal_initial_score,"
                    + "internal_final_score,parse_ms,construction_ms,local_search_ms,"
                    + "validation_scoring_ms,writing_ms,algorithm_ms,end_to_end_ms,"
                    + "ls_passes,ls_evaluated_moves,ls_accepted_moves,ls_rejected_moves,"
                    + "ls_additions,ls_replacements,ls_saved_latency_gain,ls_termination,"
                    + "ocag_placements,ocag_regret_placements,ocag_selected_static_regret");
            writer.newLine();
            writer.write(String.join(",",
                    dataset, algorithm, initialAlgorithm,
                    Long.toString(initialScore), Long.toString(finalScore),
                    milliseconds(parseNanos), milliseconds(constructionNanos),
                    milliseconds(localSearchNanos), milliseconds(validationScoringNanos),
                    milliseconds(writingNanos), milliseconds(constructionNanos + localSearchNanos),
                    milliseconds(endToEndNanos),
                    local == null ? "0" : Integer.toString(local.passes()),
                    local == null ? "0" : Long.toString(local.evaluatedMoves()),
                    local == null ? "0" : Integer.toString(local.acceptedMoves()),
                    local == null ? "0" : Long.toString(local.rejectedMoves()),
                    local == null ? "0" : Integer.toString(local.additions()),
                    local == null ? "0" : Integer.toString(local.replacements()),
                    local == null ? "0" : Long.toString(local.totalImprovement()),
                    local == null ? "not_applicable" : local.termination(),
                    ocag == null ? "0" : Integer.toString(ocag.placements()),
                    ocag == null ? "0" : Integer.toString(ocag.regretPlacements()),
                    ocag == null ? "0" : Long.toString(ocag.selectedStaticRegret())));
            writer.newLine();
        }
    }

    private static void writeLocalTrace(Path path, String dataset, String algorithm,
            ProblemInstance problem, LocalSearchSolver.SearchStatistics statistics)
            throws Exception {
        createParent(path);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("dataset,algorithm,accepted_move,pass,cache_id,move_type,"
                    + "added_video_id,removed_video_id,saved_latency_gain,"
                    + "cumulative_saved_latency,score_equivalent,elapsed_ms");
            writer.newLine();
            for (LocalSearchSolver.TracePoint point : statistics.trace()) {
                long score = problem.totalRequestCount() == 0 ? 0
                        : point.savedLatency() * 1000L / problem.totalRequestCount();
                writer.write(String.join(",", dataset, algorithm,
                        Integer.toString(point.acceptedMove()), Integer.toString(point.pass()),
                        Integer.toString(point.cacheId()), point.moveType(),
                        Integer.toString(point.addedVideoId()),
                        Integer.toString(point.removedVideoId()),
                        Long.toString(point.improvement()), Long.toString(point.savedLatency()),
                        Long.toString(score), milliseconds(point.elapsedNanos())));
                writer.newLine();
            }
        }
    }

    private static void writeOcagTrace(Path path, String dataset, String algorithm,
            OpportunityCostGreedySolver.ConstructionStatistics statistics) throws Exception {
        createParent(path);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("dataset,algorithm,placement,cache_id,video_id,video_size,"
                    + "marginal_gain,static_regret,adjusted_value,remaining_capacity");
            writer.newLine();
            for (OpportunityCostGreedySolver.PlacementTrace point : statistics.trace()) {
                writer.write(String.join(",", dataset, algorithm,
                        Integer.toString(point.placement()), Integer.toString(point.cacheId()),
                        Integer.toString(point.videoId()), Integer.toString(point.videoSize()),
                        Long.toString(point.marginalGain()), Long.toString(point.staticRegret()),
                        Long.toString(point.adjustedValue()),
                        Integer.toString(point.remainingCapacity())));
                writer.newLine();
            }
        }
    }

    private static String milliseconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static void createParent(Path path) throws Exception {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private record Construction(
            Solution solution,
            String initialAlgorithm,
            OpportunityCostGreedySolver.ConstructionStatistics ocagStatistics) { }
}
