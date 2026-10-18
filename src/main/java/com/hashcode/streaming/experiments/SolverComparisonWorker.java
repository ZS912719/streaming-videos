package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.SolverRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** One isolated comparison run, with timings independent of solver log formatting. */
public final class SolverComparisonWorker {
    private SolverComparisonWorker() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--list")) {
            SolverRegistry.names().forEach(System.out::println);
            return;
        }
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SolverComparisonWorker "
                    + "<algorithm> <input> <output> <metrics.csv> | --list");
        }
        ProblemInstance problem = new InputParser().parse(Path.of(args[1]));
        long start = System.nanoTime();
        Solution solution = SolverRegistry.solve(args[0], problem);
        long solveNanos = System.nanoTime() - start;
        new SolutionValidator().validate(problem, solution);
        long score = new ScoreCalculator().calculate(problem, solution);
        new SolutionWriter().write(Path.of(args[2]), problem, solution);
        Files.writeString(Path.of(args[3]), "score,solve_ms,non_empty_caches\n"
                + String.format(Locale.ROOT, "%d,%.3f,%d%n", score,
                        solveNanos / 1_000_000.0, solution.nonEmptyCacheCount()));
    }
}
