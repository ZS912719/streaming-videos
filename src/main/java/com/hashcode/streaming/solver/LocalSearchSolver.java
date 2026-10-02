package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Improves a greedy placement through feasible additions and one-for-one swaps. */
public final class LocalSearchSolver {
    private static final int MAX_PASSES = 3;

    public Solution solve(ProblemInstance problem) {
        return improve(problem, new GreedySolver().solve(problem));
    }

    /** Mutates the supplied placement. Every accepted move strictly increases saved latency. */
    public Solution improve(ProblemInstance problem, Solution solution) {
        List<List<Link>> links = new ArrayList<>(problem.cacheCount());
        for (int c = 0; c < problem.cacheCount(); c++) links.add(new ArrayList<>());
        for (Endpoint endpoint : problem.endpoints()) {
            for (CacheConnection connection : endpoint.connections()) {
                links.get(connection.cacheId()).add(new Link(endpoint, connection.latency()));
            }
        }

        int moves = 0;
        long improvement = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (int cache = 0; cache < problem.cacheCount(); cache++) {
                // Exclude this cache: benefits now represent exact insertion gains and removal losses.
                long[] benefits = benefitsWithoutCache(cache, problem, solution, links.get(cache));
                int used = 0;
                List<Integer> stored = new ArrayList<>(solution.videosInCache(cache));
                for (int video : stored) used += problem.videoSizes()[video];
                stored.sort(Comparator.<Integer>comparingLong(v -> benefits[v])
                        .thenComparingInt(Integer::intValue));
                int remaining = problem.cacheCapacity() - used;
                int bestAdded = -1;
                int bestRemoved = -1;
                long bestDelta = 0;
                for (int video = 0; video < problem.videoCount(); video++) {
                    if (solution.contains(cache, video) || benefits[video] <= 0) continue;
                    int size = problem.videoSizes()[video];
                    int removed = -1;
                    long loss = 0;
                    if (size > remaining) {
                        boolean fits = false;
                        // The first feasible removal has the lowest loss because stored is sorted.
                        for (int previous : stored) {
                            if (size <= remaining + problem.videoSizes()[previous]) {
                                removed = previous;
                                loss = benefits[previous];
                                fits = true;
                                break;
                            }
                        }
                        if (!fits) continue;
                    }
                    long delta = benefits[video] - loss;
                    if (delta > bestDelta) {
                        bestDelta = delta;
                        bestAdded = video;
                        bestRemoved = removed;
                    }
                }
                if (bestAdded >= 0) {
                    if (bestRemoved >= 0) solution.removeVideo(cache, bestRemoved);
                    solution.addVideo(cache, bestAdded);
                    moves++;
                    improvement += bestDelta;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        System.err.printf("Local search: %d moves | Saved latency gain: %d request-ms%n",
                moves, improvement);
        return solution;
    }

    private static long[] benefitsWithoutCache(int cache, ProblemInstance problem,
            Solution solution, List<Link> links) {
        long[] benefits = new long[problem.videoCount()];
        for (Link link : links) {
            Endpoint endpoint = link.endpoint();
            for (RequestDemand demand : endpoint.requestsByVideo().values()) {
                int best = endpoint.dataCenterLatency();
                for (CacheConnection other : endpoint.connections()) {
                    if (other.cacheId() != cache
                            && solution.contains(other.cacheId(), demand.videoId())) {
                        best = Math.min(best, other.latency());
                    }
                }
                benefits[demand.videoId()] += demand.requestCount()
                        * (long) Math.max(0, best - link.latency());
            }
        }
        return benefits;
    }

    private record Link(Endpoint endpoint, int latency) { }
}
