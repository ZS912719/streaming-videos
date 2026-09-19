package com.hashcode.streaming;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.BaselineSolver;
import com.hashcode.streaming.solver.GreedySolver;
import java.nio.file.Path;

/** Command-line entry point. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            printUsage();
            System.exit(2);
        }
        try {
            String algorithm = args.length == 2 ? "greedy" : args[0].toLowerCase();
            Path input = Path.of(args.length == 2 ? args[0] : args[1]);
            Path output = Path.of(args.length == 2 ? args[1] : args[2]);
            ProblemInstance problem = new InputParser().parse(input);
            Solution solution = switch (algorithm) {
                case "greedy" -> new GreedySolver().solve(problem);
                case "baseline" -> new BaselineSolver().solve(problem);
                default -> throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
            };
            new SolutionValidator().validate(problem, solution);
            long score = new ScoreCalculator().calculate(problem, solution);
            new SolutionWriter().write(output, problem, solution);
            System.err.printf("Algorithm: %s | Score: %,d | Non-empty caches: %d/%d%n",
                    algorithm, score, solution.nonEmptyCacheCount(), problem.cacheCount());
        } catch (Exception exception) {
            System.err.println("Failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -cp out com.hashcode.streaming.Main <input> <output>");
        System.err.println("  java -cp out com.hashcode.streaming.Main <greedy|baseline> <input> <output>");
    }
}
