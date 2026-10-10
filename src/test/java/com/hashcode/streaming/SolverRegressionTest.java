package com.hashcode.streaming;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.experiments.ExactSmallSolver;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.GreedySolver;
import com.hashcode.streaming.solver.LocalSearchSolver;
import com.hashcode.streaming.solver.OpportunityCostGreedySolver;
import java.nio.file.Path;

/** Dependency-free regression checks for the command-line solvers. */
public final class SolverRegressionTest {
    private SolverRegressionTest() { }

    public static void main(String[] args) throws Exception {
        assertScore("examples/me_at_the_zoo.in", new GreedySolver().solve(
                parse("examples/me_at_the_zoo.in")), 507_906L, "greedy reference");

        ProblemInstance localSearch = parse("examples/local-search.in");
        assertScore("examples/local-search.in", new LocalSearchSolver().solve(localSearch),
                529_411L, "local-search replacement");

        ProblemInstance opportunity = parse("examples/opportunity-cost.in");
        assertScore("examples/opportunity-cost.in", new GreedySolver().solve(opportunity),
                549_500L, "opportunity example greedy");
        opportunity = parse("examples/opportunity-cost.in");
        assertScore("examples/opportunity-cost.in",
                new OpportunityCostGreedySolver().solve(opportunity),
                925_000L, "opportunity example OCAG");
        opportunity = parse("examples/opportunity-cost.in");
        ExactSmallSolver.Result exact = new ExactSmallSolver().solve(opportunity);
        if (exact.score() != 925_000L) {
            throw new AssertionError("opportunity example optimum: expected 925000, got "
                    + exact.score());
        }
        System.out.println("All solver regression checks passed.");
    }

    private static ProblemInstance parse(String path) throws Exception {
        return new InputParser().parse(Path.of(path));
    }

    private static void assertScore(
            String input, Solution solution, long expected, String label) throws Exception {
        ProblemInstance problem = parse(input);
        new SolutionValidator().validate(problem, solution);
        long actual = new ScoreCalculator().calculate(problem, solution);
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
