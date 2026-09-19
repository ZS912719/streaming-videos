package com.hashcode.streaming;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.GreedySolver;
import java.nio.file.Path;

/** Command-line entry point. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java -cp out com.hashcode.streaming.Main <input> <output>");
            System.exit(2);
        }
        try {
            ProblemInstance problem = new InputParser().parse(Path.of(args[0]));
            Solution solution = new GreedySolver().solve(problem);
            new SolutionValidator().validate(problem, solution);
            long score = new ScoreCalculator().calculate(problem, solution);
            new SolutionWriter().write(Path.of(args[1]), problem, solution);
            System.err.printf("Score: %,d | Non-empty caches: %d/%d%n",
                    score, solution.nonEmptyCacheCount(), problem.cacheCount());
        } catch (Exception exception) {
            System.err.println("Failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
