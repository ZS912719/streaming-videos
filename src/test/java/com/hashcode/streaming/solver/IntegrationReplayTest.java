package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.*;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.scoring.SolutionValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Run against both the frozen and integrated classpaths; fingerprints must match. */
public final class IntegrationReplayTest {
    private static final StringBuilder RESULTS = new StringBuilder();

    public static void main(String[] args) throws Exception {
        int searches = 0;
        for (int seed = 0; seed < 40; seed++) {
            Random random = new Random(49000 + seed);
            int[] sizes = new int[8];
            for (int v = 0; v < sizes.length; v++) sizes[v] = 1 + random.nextInt(9);
            List<Endpoint> endpoints = new ArrayList<>();
            long total = 0;
            for (int e = 0; e < 5; e++) {
                Endpoint endpoint = new Endpoint(100, 4);
                for (int c = 0; c < 4; c++) if (random.nextBoolean())
                    endpoint.addConnection(new CacheConnection(c, 10 + random.nextInt(5) * 10));
                for (int v = 0; v < sizes.length; v++) {
                    int count = random.nextInt(12);
                    if (count > 0) endpoint.addRequest(v, count);
                    total += count;
                }
                endpoints.add(endpoint);
            }
            ProblemInstance problem = new ProblemInstance(8, 5, 4, 18, sizes, endpoints, total);
            Solution initial = new GreedySolver().solve(problem);
            append(problem, initial);
            append(problem, new LocalSearchSolver().improveWithStatistics(problem,
                    GeneralSolver.copy(problem, initial), LocalSearchSolver.Neighborhood.COMPLETE).solution());
            for (var selection : LocalSearchSolver.Selection.values())
                for (var order : LocalSearchSolver.CacheOrder.values())
                    for (var candidates : LocalSearchSolver.CandidateOrder.values())
                        for (int moves : new int[]{1, 3})
                            for (var neighborhood : List.of(LocalSearchSolver.Neighborhood.COMPLETE,
                                    LocalSearchSolver.Neighborhood.INSERTION_ONLY,
                                    LocalSearchSolver.Neighborhood.REPLACEMENT_ONLY)) {
                                var result = new LocalSearchSolver().improveWithStatistics(problem,
                                        GeneralSolver.copy(problem, initial), neighborhood,
                                        new LocalSearchSolver.Settings(5, moves, selection, order, candidates));
                                append(problem, result.solution());
                                for (var event : result.statistics().trace()) RESULTS.append(event.pass())
                                        .append(':').append(event.cacheId()).append(':').append(event.addedVideoId())
                                        .append(':').append(event.removedVideoId()).append(':').append(event.improvement()).append(';');
                                RESULTS.append(result.statistics().termination());
                                searches++;
                            }
            var v1 = new GeneralSolver().solve(problem,
                    new GeneralSolver.Settings(GeneralSolver.Mode.PORTFOLIO, 100000, 42,
                            true, true, false, "all", true, 1000), null);
            append(problem, v1.solution());
            var v2 = new UltimateV2Solver().solve(problem,
                    new UltimateV2Solver.Settings(UltimateV2Solver.Policy.FIXED, 100000, 42,
                            true, 1000, true, true, true, true, false, true, true), null);
            append(problem, v2.solution());
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(RESULTS.toString().getBytes(StandardCharsets.UTF_8)));
        System.out.println(searches + " tuned LS replays and 80 fixed-work V1/V2 replays: " + digest);
    }

    private static void append(ProblemInstance problem, Solution solution) {
        new SolutionValidator().validate(problem, solution);
        for (int cache = 0; cache < problem.cacheCount(); cache++) {
            List<Integer> videos = new ArrayList<>(solution.videosInCache(cache));
            Collections.sort(videos);
            RESULTS.append(cache).append(videos).append(';');
        }
    }
}
