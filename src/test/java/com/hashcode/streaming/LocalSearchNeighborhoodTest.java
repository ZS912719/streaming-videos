package com.hashcode.streaming;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.experiments.NeighborhoodAudit;
import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import com.hashcode.streaming.solver.GreedySolver;
import com.hashcode.streaming.solver.LocalSearchSolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Exact tiny-neighborhood oracles and replay checks for bounded replacements. */
final class LocalSearchNeighborhoodTest {
    private LocalSearchNeighborhoodTest() { }

    static void run() throws Exception {
        var solver = new LocalSearchSolver();
        var problem = new InputParser().parse(Path.of("examples/two-removal.in"));
        var seed = new GreedySolver().solve(problem);
        var single = solver.improveWithStatistics(problem, copy(problem, seed),
                LocalSearchSolver.Neighborhood.COMPLETE);
        var pair = solver.improveWithStatistics(problem, copy(problem, seed),
                LocalSearchSolver.Neighborhood.TWO_REMOVALS);
        require(single.statistics().acceptedMoves() == 0, "one removal must be trapped");
        require(pair.statistics().doubleReplacements() == 1, "two removals must escape");
        require(pair.statistics().totalImprovement() == 900L, "exact pair gain");
        replay(problem, seed, pair, 2);

        Endpoint endpoint = new Endpoint(1000, 1);
        endpoint.addConnection(new CacheConnection(0, 100));
        endpoint.addRequest(0, 8);
        endpoint.addRequest(1, 3);
        endpoint.addRequest(2, 3);
        endpoint.addRequest(3, 10);
        var cheaperPair = new ProblemInstance(4, 1, 1, 16,
                new int[]{8, 3, 3, 8}, List.of(endpoint), 24);
        var full = new Solution(1);
        full.addVideo(0, 0); full.addVideo(0, 1); full.addVideo(0, 2);
        var result = solver.improveWithStatistics(cheaperPair, copy(cheaperPair, full),
                LocalSearchSolver.Neighborhood.TWO_REMOVALS);
        require(result.statistics().trace().get(1).secondRemovedVideoId() >= 0,
                "consider pairs even when a single removal is feasible");
        require(result.statistics().trace().get(1).improvement() == 3600,
                "choose the cheaper pair instead of the expensive single");
        replay(cheaperPair, full, result, 2);

        var zero = solver.improveWithinBudget(problem, copy(problem, seed),
                LocalSearchSolver.Neighborhood.TWO_REMOVALS, 0L);
        require(zero.statistics().acceptedMoves() == 0, "zero budget changes no placement");
        require(zero.statistics().termination().equals("time_budget"), "budget stop reason");
        require(saved(problem, zero.solution()) == saved(problem, seed), "budget seed retained");

        // Covers overlapping copies, equal link latencies, spare space and varied sizes.
        Random random = new Random(78123L);
        for (int trial = 0; trial < 40; trial++) {
            int[] sizes = new int[7];
            for (int i = 0; i < sizes.length; i++) sizes[i] = 1 + random.nextInt(8);
            List<Endpoint> endpoints = new ArrayList<>();
            long requests = 0;
            for (int e = 0; e < 4; e++) {
                Endpoint view = new Endpoint(1000, 3);
                for (int c = 0; c < 3; c++) {
                    if (random.nextBoolean()) view.addConnection(new CacheConnection(c,
                            100 + 200 * random.nextInt(4)));
                }
                for (int v = 0; v < sizes.length; v++) {
                    int count = random.nextInt(20);
                    if (count > 0) view.addRequest(v, count);
                    requests += count;
                }
                endpoints.add(view);
            }
            var tiny = new ProblemInstance(7, 4, 3, 14, sizes, endpoints, requests);
            var placement = new Solution(3);
            for (int c = 0; c < 3; c++) {
                int used = 0;
                for (int v = 0; v < sizes.length; v++) {
                    if (random.nextBoolean() && used + sizes[v] <= 14) {
                        placement.addVideo(c, v);
                        used += sizes[v];
                    }
                }
            }
            for (var neighborhood : List.of(LocalSearchSolver.Neighborhood.COMPLETE,
                    LocalSearchSolver.Neighborhood.TWO_REMOVALS)) {
                int limit = neighborhood == LocalSearchSolver.Neighborhood.COMPLETE ? 1 : 2;
                long exactMaximum = 0;
                for (int c = 0; c < tiny.cacheCount(); c++) {
                    exactMaximum = Math.max(exactMaximum, bestDelta(tiny, placement, c, limit));
                }
                require(NeighborhoodAudit.audit(tiny, placement, limit).maximumGain() == exactMaximum,
                        "independent suffix audit matches exhaustive oracle");
                var search = solver.improveWithStatistics(tiny, copy(tiny, placement), neighborhood);
                replay(tiny, placement, search, limit);
                if (search.statistics().termination().equals("no_improving_move")) {
                    require(NeighborhoodAudit.audit(tiny, search.solution(), limit).maximumGain() == 0,
                            "independent convergence verification");
                }
            }
        }
        System.out.println("Local-search exact-neighborhood and deadline checks passed.");
    }

    private static void replay(ProblemInstance problem, Solution seed,
            LocalSearchSolver.SearchResult result, int removalLimit) {
        Solution current = copy(problem, seed);
        long initial = saved(problem, current);
        for (var point : result.statistics().trace()) {
            if (point.acceptedMove() == 0) continue;
            require(point.improvement() == bestDelta(problem, current, point.cacheId(), removalLimit),
                    "selected move must match the exhaustive cache-neighborhood oracle");
            long before = saved(problem, current);
            require(!current.contains(point.cacheId(), point.addedVideoId()), "new video absent");
            if (point.removedVideoId() >= 0) {
                require(current.removeVideo(point.cacheId(), point.removedVideoId()), "first removal");
            }
            if (point.secondRemovedVideoId() >= 0) {
                require(current.removeVideo(point.cacheId(), point.secondRemovedVideoId()), "second removal");
            }
            current.addVideo(point.cacheId(), point.addedVideoId());
            new SolutionValidator().validate(problem, current);
            long after = saved(problem, current);
            require(after - before == point.improvement() && after > before, "exact positive delta");
            require(after == point.savedLatency(), "trace cumulative saving");
        }
        require(saved(problem, current) - initial == result.statistics().totalImprovement(),
                "trace improvement total");
        for (int c = 0; c < problem.cacheCount(); c++) {
            require(current.videosInCache(c).equals(result.solution().videosInCache(c)), "trace final placement");
            if (result.statistics().termination().equals("no_improving_move")) {
                require(bestDelta(problem, current, c, removalLimit) == 0, "claimed local optimum");
            }
        }
    }

    private static long bestDelta(ProblemInstance problem, Solution current, int cache, int limit) {
        long before = saved(problem, current), best = 0;
        List<Integer> removals = new ArrayList<>(current.videosInCache(cache));
        removals.add(-1);
        for (int added = 0; added < problem.videoCount(); added++) {
            if (current.contains(cache, added)) continue;
            for (int i = 0; i < removals.size(); i++) {
                int first = removals.get(i);
                best = Math.max(best, trialDelta(problem, current, cache, added, first, -1, before));
                if (limit == 2 && first >= 0) {
                    for (int j = i + 1; j < removals.size(); j++) {
                        int second = removals.get(j);
                        if (second >= 0) best = Math.max(best,
                                trialDelta(problem, current, cache, added, first, second, before));
                    }
                }
            }
        }
        return best;
    }

    private static long trialDelta(ProblemInstance problem, Solution current, int cache,
            int added, int first, int second, long before) {
        Solution candidate = copy(problem, current);
        if (first >= 0) candidate.removeVideo(cache, first);
        if (second >= 0) candidate.removeVideo(cache, second);
        candidate.addVideo(cache, added);
        long used = 0;
        for (int video : candidate.videosInCache(cache)) used += problem.videoSizes()[video];
        return used <= problem.cacheCapacity() ? saved(problem, candidate) - before : Long.MIN_VALUE;
    }

    private static long saved(ProblemInstance problem, Solution solution) {
        long sum = 0;
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand demand : endpoint.requestsByVideo().values()) {
                int best = endpoint.dataCenterLatency();
                for (CacheConnection link : endpoint.connections()) {
                    if (solution.contains(link.cacheId(), demand.videoId())) best = Math.min(best, link.latency());
                }
                sum += demand.requestCount() * (endpoint.dataCenterLatency() - (long) best);
            }
        }
        return sum;
    }

    private static Solution copy(ProblemInstance problem, Solution source) {
        var result = new Solution(problem.cacheCount());
        for (int c = 0; c < problem.cacheCount(); c++) {
            for (int video : source.videosInCache(c)) result.addVideo(c, video);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
