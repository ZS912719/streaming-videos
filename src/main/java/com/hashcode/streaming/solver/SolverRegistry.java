package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Shared registration point for command-line solving and batch comparisons. */
public final class SolverRegistry {
    private static final Map<String, Function<ProblemInstance, Solution>> SOLVERS;

    static {
        Map<String, Function<ProblemInstance, Solution>> solvers = new LinkedHashMap<>();
        solvers.put("baseline", problem -> new BaselineSolver().solve(problem));
        solvers.put("greedy", problem -> new GreedySolver().solve(problem));
        solvers.put("ocag", problem -> new OpportunityCostGreedySolver().solve(problem));
        solvers.put("local-search", problem -> new LocalSearchSolver().solve(problem));
        solvers.put("ocag-ls", problem -> new LocalSearchSolver().improve(
                problem, new OpportunityCostGreedySolver().solve(problem)));
        solvers.put("local-search-two", problem -> new LocalSearchSolver().improveWithStatistics(
                problem, new GreedySolver().solve(problem),
                LocalSearchSolver.Neighborhood.TWO_REMOVALS).solution());
        // Register a new strategy here; the comparison script discovers this list automatically.
        SOLVERS = Collections.unmodifiableMap(solvers);
    }

    private SolverRegistry() { }

    public static Set<String> names() { return SOLVERS.keySet(); }

    public static Solution solve(String name, ProblemInstance problem) {
        Function<ProblemInstance, Solution> solver = SOLVERS.get(name);
        if (solver == null) throw new IllegalArgumentException("Unknown algorithm: " + name);
        return solver.apply(problem);
    }
}
