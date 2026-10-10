package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Improves a supplied placement through feasible additions and one-for-one swaps. */
public final class LocalSearchSolver {
    private static final int MAX_PASSES = 3;

    public enum Neighborhood {
        COMPLETE,
        INSERTION_ONLY,
        REPLACEMENT_ONLY
    }

    public Solution solve(ProblemInstance problem) {
        return improve(problem, new GreedySolver().solve(problem));
    }

    /** Mutates the supplied placement. Every accepted move strictly increases saved latency. */
    public Solution improve(ProblemInstance problem, Solution solution) {
        SearchResult result = improveWithStatistics(problem, solution, Neighborhood.COMPLETE);
        SearchStatistics statistics = result.statistics();
        System.err.printf("Local search: %d moves | Saved latency gain: %d request-ms%n",
                statistics.acceptedMoves(), statistics.totalImprovement());
        return result.solution();
    }

    /** Runs the same search while collecting compact aggregate and anytime statistics. */
    public SearchResult improveWithStatistics(
            ProblemInstance problem, Solution solution, Neighborhood neighborhood) {
        long searchStart = System.nanoTime();
        SearchIndex index = buildSearchIndex(problem, solution);
        long initialSavedLatency = savedLatency(index);
        List<TracePoint> trace = new ArrayList<>();
        trace.add(new TracePoint(0, 0, -1, "initial", -1, -1, 0,
                initialSavedLatency, 0L));

        long evaluatedMoves = 0L;
        int acceptedMoves = 0;
        int additions = 0;
        int replacements = 0;
        long totalImprovement = 0L;
        int passes = 0;
        String termination = "max_passes";

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            passes++;
            boolean changed = false;
            for (int cache = 0; cache < problem.cacheCount(); cache++) {
                long[] benefits = benefitsWithoutCache(cache, problem, index);
                int used = 0;
                List<Integer> stored = new ArrayList<>(solution.videosInCache(cache));
                for (int video : stored) used += problem.videoSizes()[video];
                stored.sort(Comparator.<Integer>comparingLong(video -> benefits[video])
                        .thenComparingInt(Integer::intValue));
                int remaining = problem.cacheCapacity() - used;
                int bestAdded = -1;
                int bestRemoved = -1;
                long bestDelta = 0L;

                for (int video = 0; video < problem.videoCount(); video++) {
                    if (solution.contains(cache, video) || benefits[video] <= 0) continue;
                    evaluatedMoves++;
                    int size = problem.videoSizes()[video];
                    int removed = -1;
                    long loss = 0L;
                    if (size <= remaining) {
                        if (neighborhood == Neighborhood.REPLACEMENT_ONLY) continue;
                    } else {
                        if (neighborhood == Neighborhood.INSERTION_ONLY) continue;
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
                    if (bestRemoved >= 0) {
                        solution.removeVideo(cache, bestRemoved);
                        replacements++;
                    } else {
                        additions++;
                    }
                    solution.addVideo(cache, bestAdded);
                    refreshDemandStates(cache, bestAdded, solution, index);
                    if (bestRemoved >= 0) {
                        refreshDemandStates(cache, bestRemoved, solution, index);
                    }
                    acceptedMoves++;
                    totalImprovement += bestDelta;
                    changed = true;
                    long currentSavedLatency = initialSavedLatency + totalImprovement;
                    trace.add(new TracePoint(acceptedMoves, pass + 1, cache,
                            bestRemoved < 0 ? "insertion" : "replacement",
                            bestAdded, bestRemoved, bestDelta, currentSavedLatency,
                            System.nanoTime() - searchStart));
                }
            }
            if (!changed) {
                termination = "no_improving_move";
                break;
            }
        }

        long elapsedNanos = System.nanoTime() - searchStart;
        SearchStatistics statistics = new SearchStatistics(
                neighborhood.name().toLowerCase(), passes, evaluatedMoves, acceptedMoves,
                evaluatedMoves - acceptedMoves, additions, replacements,
                initialSavedLatency, totalImprovement,
                initialSavedLatency + totalImprovement, elapsedNanos, termination,
                List.copyOf(trace));
        return new SearchResult(solution, statistics);
    }

    private static SearchIndex buildSearchIndex(ProblemInstance problem, Solution solution) {
        List<EndpointView> endpointViews = new ArrayList<>(problem.endpointCount());
        Map<RequestDemand, Integer> demandIds = new IdentityHashMap<>();
        int demandCount = 0;
        for (Endpoint endpoint : problem.endpoints()) {
            int size = endpoint.requestsByVideo().size();
            int[] ids = new int[size];
            int[] videoIds = new int[size];
            long[] requestCounts = new long[size];
            int position = 0;
            for (RequestDemand demand : endpoint.requestsByVideo().values()) {
                int demandId = demandCount++;
                ids[position] = demandId;
                videoIds[position] = demand.videoId();
                requestCounts[position] = demand.requestCount();
                demandIds.put(demand, demandId);
                position++;
            }
            endpointViews.add(new EndpointView(endpoint, ids, videoIds, requestCounts));
        }

        List<List<Link>> links = new ArrayList<>(problem.cacheCount());
        for (int cache = 0; cache < problem.cacheCount(); cache++) links.add(new ArrayList<>());
        for (EndpointView view : endpointViews) {
            for (CacheConnection connection : view.endpoint().connections()) {
                links.get(connection.cacheId()).add(new Link(view, connection.latency()));
            }
        }

        int[] bestLatencies = new int[demandCount];
        int[] secondBestLatencies = new int[demandCount];
        int[] bestCacheIds = new int[demandCount];
        java.util.Arrays.fill(bestCacheIds, -1);
        for (EndpointView view : endpointViews) {
            for (int position = 0; position < view.demandIds().length; position++) {
                recomputeDemand(view.demandIds()[position], view.endpoint(),
                        view.videoIds()[position], solution,
                        bestLatencies, secondBestLatencies, bestCacheIds);
            }
        }
        return new SearchIndex(links, demandIds, bestLatencies,
                secondBestLatencies, bestCacheIds, endpointViews);
    }

    private static long savedLatency(SearchIndex index) {
        long saved = 0L;
        for (EndpointView view : index.endpointViews()) {
            for (int position = 0; position < view.demandIds().length; position++) {
                int demandId = view.demandIds()[position];
                saved += view.requestCounts()[position]
                        * (long) (view.endpoint().dataCenterLatency()
                        - index.bestLatencies()[demandId]);
            }
        }
        return saved;
    }

    private static long[] benefitsWithoutCache(
            int cache, ProblemInstance problem, SearchIndex index) {
        long[] benefits = new long[problem.videoCount()];
        for (Link link : index.links().get(cache)) {
            EndpointView view = link.endpointView();
            for (int position = 0; position < view.demandIds().length; position++) {
                int demandId = view.demandIds()[position];
                int bestWithoutCache = index.bestCacheIds()[demandId] == cache
                        ? index.secondBestLatencies()[demandId]
                        : index.bestLatencies()[demandId];
                benefits[view.videoIds()[position]] += view.requestCounts()[position]
                        * (long) Math.max(0, bestWithoutCache - link.latency());
            }
        }
        return benefits;
    }

    private static void refreshDemandStates(int cache, int videoId,
            Solution solution, SearchIndex index) {
        for (Link link : index.links().get(cache)) {
            RequestDemand demand = link.endpointView().endpoint().requestsByVideo().get(videoId);
            if (demand == null) continue;
            Integer demandId = index.demandIds().get(demand);
            if (demandId == null) continue;
            recomputeDemand(demandId, link.endpointView().endpoint(), videoId, solution,
                    index.bestLatencies(), index.secondBestLatencies(), index.bestCacheIds());
        }
    }

    private static void recomputeDemand(int demandId, Endpoint endpoint, int videoId,
            Solution solution, int[] bestLatencies, int[] secondBestLatencies,
            int[] bestCacheIds) {
        int best = endpoint.dataCenterLatency();
        int secondBest = endpoint.dataCenterLatency();
        int bestCache = -1;
        for (CacheConnection connection : endpoint.connections()) {
            if (!solution.contains(connection.cacheId(), videoId)) continue;
            int latency = connection.latency();
            if (latency < best) {
                secondBest = best;
                best = latency;
                bestCache = connection.cacheId();
            } else if (latency < secondBest) {
                secondBest = latency;
            }
        }
        bestLatencies[demandId] = best;
        secondBestLatencies[demandId] = secondBest;
        bestCacheIds[demandId] = bestCache;
    }

    private record EndpointView(Endpoint endpoint, int[] demandIds,
            int[] videoIds, long[] requestCounts) { }

    private record Link(EndpointView endpointView, int latency) { }

    private record SearchIndex(
            List<List<Link>> links,
            Map<RequestDemand, Integer> demandIds,
            int[] bestLatencies,
            int[] secondBestLatencies,
            int[] bestCacheIds,
            List<EndpointView> endpointViews) { }

    public record TracePoint(
            int acceptedMove,
            int pass,
            int cacheId,
            String moveType,
            int addedVideoId,
            int removedVideoId,
            long improvement,
            long savedLatency,
            long elapsedNanos) { }

    public record SearchStatistics(
            String neighborhood,
            int passes,
            long evaluatedMoves,
            int acceptedMoves,
            long rejectedMoves,
            int additions,
            int replacements,
            long initialSavedLatency,
            long totalImprovement,
            long finalSavedLatency,
            long elapsedNanos,
            String termination,
            List<TracePoint> trace) { }

    public record SearchResult(Solution solution, SearchStatistics statistics) { }
}
