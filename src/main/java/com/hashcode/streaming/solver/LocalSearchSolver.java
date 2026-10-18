package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Improves a placement through additions and bounded one- or two-video removals. */
public final class LocalSearchSolver {
    private static final int MAX_PASSES = 20;

    public enum Neighborhood {
        COMPLETE,
        INSERTION_ONLY,
        REPLACEMENT_ONLY,
        TWO_REMOVALS
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
        return search(problem, solution, neighborhood, MAX_PASSES, -1L);
    }

    /**
     * Uses a shared wall-clock repair budget instead of a pass cap. Initialization is
     * included; parsing, seed construction and output are excluded. Deadline checks
     * are cooperative: initialization and an accepted move's atomic refresh may overrun.
     */
    public SearchResult improveWithinBudget(ProblemInstance problem, Solution solution,
            Neighborhood neighborhood, long budgetNanos) {
        if (budgetNanos < 0) throw new IllegalArgumentException("Negative search budget");
        return search(problem, solution, neighborhood, Integer.MAX_VALUE, budgetNanos);
    }

    private SearchResult search(ProblemInstance problem, Solution solution,
            Neighborhood neighborhood, int maxPasses, long budgetNanos) {
        long searchStart = System.nanoTime();
        Deadline deadline = new Deadline(searchStart, budgetNanos);
        SearchIndex index = buildSearchIndex(problem, solution);
        long initialSavedLatency = savedLatency(index);
        List<TracePoint> trace = new ArrayList<>();
        trace.add(new TracePoint(0, 0, -1, "initial", -1, -1, -1, 0,
                initialSavedLatency, 0L));

        long evaluatedMoves = 0L;
        int acceptedMoves = 0;
        int additions = 0;
        int replacements = 0;
        int doubleReplacements = 0;
        long[] evaluatedRemovalPairs = {0L};
        long totalImprovement = 0L;
        int passes = 0;
        String termination = "max_passes";

        try {
            deadline.check();
            for (int pass = 0; pass < maxPasses; pass++) {
                passes++;
                boolean changed = false;
                for (int cache = 0; cache < problem.cacheCount(); cache++) {
                    deadline.check();
                    long[] benefits = benefitsWithoutCache(cache, problem, index, deadline);
                    int used = 0;
                    List<Integer> stored = new ArrayList<>(solution.videosInCache(cache));
                    for (int video : stored) used += problem.videoSizes()[video];
                    stored.sort(Comparator.<Integer>comparingLong(video -> benefits[video])
                            .thenComparingInt(Integer::intValue));
                    int remaining = problem.cacheCapacity() - used;
                    int bestAdded = -1;
                    int bestRemoved = -1;
                    int bestSecondRemoved = -1;
                    long bestDelta = 0L;
                    RemovalTable removals = neighborhood == Neighborhood.TWO_REMOVALS
                            ? buildRemovalTable(problem, solution, cache, stored, remaining,
                                    benefits, deadline, evaluatedRemovalPairs) : null;

                    for (int video = 0; video < problem.videoCount(); video++) {
                        if ((video & 127) == 0) deadline.check();
                        if (solution.contains(cache, video) || benefits[video] <= 0) continue;
                        evaluatedMoves++;
                        int size = problem.videoSizes()[video];
                        int removed = -1;
                        int secondRemoved = -1;
                        long loss = 0L;
                        if (size <= remaining) {
                            if (neighborhood == Neighborhood.REPLACEMENT_ONLY) continue;
                        } else {
                            if (neighborhood == Neighborhood.INSERTION_ONLY) continue;
                            if (removals != null) {
                                int option = Arrays.binarySearch(removals.required, size - remaining);
                                if (option < 0 || removals.first[option] < 0) continue;
                                removed = removals.first[option];
                                secondRemoved = removals.second[option];
                                loss = removals.loss[option];
                            } else {
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
                        }
                        long delta = benefits[video] - loss;
                        if (delta > bestDelta) {
                            bestDelta = delta;
                            bestAdded = video;
                            bestRemoved = removed;
                            bestSecondRemoved = secondRemoved;
                        }
                    }

                    // Never commit a partially evaluated cache or interrupt a placement update.
                    deadline.check();
                    if (bestAdded >= 0) {
                        if (bestRemoved >= 0) {
                            solution.removeVideo(cache, bestRemoved);
                            replacements++;
                        } else {
                            additions++;
                        }
                        if (bestSecondRemoved >= 0) {
                            solution.removeVideo(cache, bestSecondRemoved);
                            doubleReplacements++;
                        }
                        solution.addVideo(cache, bestAdded);
                        refreshDemandStates(cache, bestAdded, solution, index);
                        if (bestRemoved >= 0) {
                            refreshDemandStates(cache, bestRemoved, solution, index);
                        }
                        if (bestSecondRemoved >= 0) {
                            refreshDemandStates(cache, bestSecondRemoved, solution, index);
                        }
                        acceptedMoves++;
                        totalImprovement += bestDelta;
                        changed = true;
                        long currentSavedLatency = initialSavedLatency + totalImprovement;
                        trace.add(new TracePoint(acceptedMoves, pass + 1, cache,
                                bestRemoved < 0 ? "insertion"
                                        : bestSecondRemoved < 0 ? "replacement" : "replacement_two",
                                bestAdded, bestRemoved, bestSecondRemoved, bestDelta, currentSavedLatency,
                                System.nanoTime() - searchStart));
                    }
                }
                if (!changed) {
                    termination = "no_improving_move";
                    break;
                }
            }
        } catch (BudgetExpired ignored) {
            termination = "time_budget";
        }

        long elapsedNanos = System.nanoTime() - searchStart;
        SearchStatistics statistics = new SearchStatistics(
                neighborhood.name().toLowerCase(), passes, evaluatedMoves, acceptedMoves,
                evaluatedMoves - acceptedMoves, additions, replacements,
                initialSavedLatency, totalImprovement,
                initialSavedLatency + totalImprovement, elapsedNanos, termination,
                List.copyOf(trace), doubleReplacements, evaluatedRemovalPairs[0]);
        return new SearchResult(solution, statistics);
    }

    /** One loss bucket per distinct required space, so memory is O(videoCount). */
    private static RemovalTable buildRemovalTable(ProblemInstance problem, Solution solution,
            int cache, List<Integer> stored, int remaining, long[] benefits, Deadline deadline,
            long[] evaluatedPairs) {
        int[] needed = new int[problem.videoCount()];
        int count = 0;
        for (int video = 0; video < problem.videoCount(); video++) {
            if ((video & 127) == 0) deadline.check();
            if (!solution.contains(cache, video) && benefits[video] > 0
                    && problem.videoSizes()[video] > remaining) {
                needed[count++] = problem.videoSizes()[video] - remaining;
            }
        }
        Arrays.sort(needed, 0, count);
        int unique = 0;
        for (int i = 0; i < count; i++) {
            if (unique == 0 || needed[i] != needed[unique - 1]) needed[unique++] = needed[i];
        }
        RemovalTable table = new RemovalTable(Arrays.copyOf(needed, unique));
        if (unique == 0) return table;
        for (int video : stored) {
            deadline.check();
            table.consider(problem.videoSizes()[video], benefits[video], video, -1);
        }
        for (int i = 0; i < stored.size(); i++) {
            deadline.check();
            int first = stored.get(i);
            for (int j = i + 1; j < stored.size(); j++) {
                if ((j & 255) == 0) deadline.check();
                int second = stored.get(j);
                evaluatedPairs[0]++;
                table.consider(problem.videoSizes()[first] + problem.videoSizes()[second],
                        benefits[first] + benefits[second], Math.min(first, second),
                        Math.max(first, second));
            }
        }
        // An option releasing more space also satisfies every smaller requirement.
        for (int i = unique - 2; i >= 0; i--) {
            if (table.better(i, table.loss[i + 1], table.first[i + 1], table.second[i + 1])) {
                table.set(i, table.loss[i + 1], table.first[i + 1], table.second[i + 1]);
            }
        }
        return table;
    }

    private static final class RemovalTable {
        final int[] required, first, second;
        final long[] loss;

        RemovalTable(int[] required) {
            this.required = required;
            first = new int[required.length];
            second = new int[required.length];
            loss = new long[required.length];
            Arrays.fill(first, -1);
            Arrays.fill(second, -1);
            Arrays.fill(loss, Long.MAX_VALUE);
        }

        void consider(int released, long cost, int a, int b) {
            int position = Arrays.binarySearch(required, released);
            if (position < 0) position = -position - 2;
            if (position >= 0 && better(position, cost, a, b)) set(position, cost, a, b);
        }

        boolean better(int position, long cost, int a, int b) {
            if (a < 0) return false;
            if (cost != loss[position]) return cost < loss[position];
            if ((b < 0) != (second[position] < 0)) return b < 0;
            return a < first[position] || (a == first[position] && b < second[position]);
        }

        void set(int position, long cost, int a, int b) {
            loss[position] = cost;
            first[position] = a;
            second[position] = b;
        }
    }

    private record Deadline(long start, long budget) {
        void check() {
            if (budget >= 0 && System.nanoTime() - start >= budget) throw new BudgetExpired();
        }
    }

    private static final class BudgetExpired extends RuntimeException {
        BudgetExpired() { super(null, null, false, false); }
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
            int cache, ProblemInstance problem, SearchIndex index, Deadline deadline) {
        long[] benefits = new long[problem.videoCount()];
        int scanned = 0;
        for (Link link : index.links().get(cache)) {
            EndpointView view = link.endpointView();
            for (int position = 0; position < view.demandIds().length; position++) {
                if ((scanned++ & 1023) == 0) deadline.check();
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
            int secondRemovedVideoId,
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
            List<TracePoint> trace,
            int doubleReplacements,
            long evaluatedRemovalPairs) { }

    public record SearchResult(Solution solution, SearchStatistics statistics) { }
}
