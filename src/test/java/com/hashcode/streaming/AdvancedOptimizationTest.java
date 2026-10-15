package com.hashcode.streaming;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.experiments.ExactSmallSolver;
import com.hashcode.streaming.model.*;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.*;
import com.hashcode.streaming.scoring.*;
import java.nio.file.Path;
import java.util.*;

/** Differential tests exercise changing feasible alternatives, ties, overlap and replication. */
public final class AdvancedOptimizationTest {
    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 80; seed++) {
            Random random = new Random(seed);
            int videos = 3 + random.nextInt(5), caches = 2 + random.nextInt(4);
            int[] sizes = new int[videos];
            for (int v = 0; v < videos; v++) sizes[v] = 1 + random.nextInt(10);
            List<Endpoint> endpoints = new ArrayList<>();
            long total = 0;
            for (int e = 0; e < 5; e++) {
                Endpoint endpoint = new Endpoint(100, caches);
                for (int c = 0; c < caches; c++) if (random.nextBoolean()) endpoint.addConnection(new CacheConnection(c, 10 + random.nextInt(5) * 10));
                for (int v = 0; v < videos; v++) {
                    long count = random.nextInt(10);
                    if (count > 0) { endpoint.addRequest(v, count); total += count; }
                }
                endpoints.add(endpoint);
            }
            ProblemInstance p = new ProblemInstance(videos, endpoints.size(), caches, 12, sizes, endpoints, total);
            for (double weight : new double[]{0, 0.5, 1, 2}) {
                DynamicOcagSolver.Result dynamic = new DynamicOcagSolver().solve(p, weight, true);
                new SolutionValidator().validate(p, dynamic.solution());
                new DynamicOcagSolver().solve(p, weight, true, true);
            }
            for (boolean first : new boolean[]{false, true}) {
                Solution start = new GreedySolver().solve(p);
                new IndexedExchangeSolver().improve(p, start,
                        new IndexedExchangeSolver.Settings(100, 100, 5, first, 0, true));
                new SolutionValidator().validate(p, start);
            }
            for (String neighborhood : List.of("both", "two-for-one", "one-for-two")) {
                Solution start = new GreedySolver().solve(p);
                new PackingSolver().improve(p, start, 5, 10, neighborhood, true);
                new SolutionValidator().validate(p, start);
            }
            Solution repack = new GreedySolver().solve(p);
            new CacheRepackSolver().improve(p, repack, 5, 100, true);
            new SolutionValidator().validate(p, repack);
        }
        ProblemInstance tiny = new InputParser().parse(Path.of("examples/opportunity-cost.in"));
        DynamicOcagSolver.Result dynamicTiny = new DynamicOcagSolver().solve(tiny, 1.0, true);
        if (new ScoreCalculator().calculate(tiny, dynamicTiny.solution()) != 925000) throw new AssertionError("Disappearing alternative example");
        Solution start = new GreedySolver().solve(tiny);
        IndexedExchangeSolver.Result result = new IndexedExchangeSolver().improve(tiny, start,
                new IndexedExchangeSolver.Settings(10, 10, 5, false, 0, true));
        if (new ScoreCalculator().calculate(tiny, result.solution()) != 925000) throw new AssertionError("Exact exchange example");
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(9000 + seed);
            int[] sizes = new int[6];
            Endpoint endpoint = new Endpoint(100, 1);
            endpoint.addConnection(new CacheConnection(0, 10));
            long total = 0;
            for (int v = 0; v < sizes.length; v++) {
                sizes[v] = 1 + random.nextInt(10);
                int count = 1 + random.nextInt(100);
                endpoint.addRequest(v, count); total += count;
            }
            ProblemInstance p = new ProblemInstance(6, 1, 1, 15, sizes, List.of(endpoint), total);
            Solution repack = new CacheRepackSolver().improve(p, new Solution(1), 2, 100, true).solution();
            if (new ScoreCalculator().calculate(p, repack) != new ExactSmallSolver().solve(p).score())
                throw new AssertionError("Repack differs from exhaustive one-cache optimum");
        }
        System.out.println("Advanced differential checks passed: 640 constructor traces, 160 exchange searches, 240 packing searches, 80 repacks and 20 exhaustive knapsack comparisons.");
    }
}
