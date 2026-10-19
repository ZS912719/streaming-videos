package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.*;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Exact dynamic alternative regret with explicit gain and feasibility invalidation. */
public final class DynamicOcagSolver {
    private static final Comparator<Entry> ORDER = Comparator
            .comparingDouble(Entry::priority).reversed()
            .thenComparing(Comparator.comparingLong(Entry::gain).reversed())
            .thenComparingInt(Entry::video).thenComparingInt(Entry::cache);

    public Solution solve(ProblemInstance problem) { return solve(problem, 1.0, false).solution(); }

    /** verify=true independently scans every cache/video priority before each placement. */
    public Result solve(ProblemInstance problem, double weight, boolean verify) {
        return solve(problem, weight, verify, false);
    }

    /** staticControl uses the same exact indexing and tie rules with fixed initial regret. */
    public Result solve(ProblemInstance problem, double weight, boolean verify, boolean staticControl) {
        if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Nonnegative finite weight required");
        long start = System.nanoTime();
        PlacementState state = new PlacementState(problem, new Solution(problem.cacheCount()));
        int vCount = state.videos, cCount = state.caches;
        long[] gains = new long[Math.multiplyExact(vCount, cCount)];
        long[] initialBest = new long[vCount], initialSecond = new long[vCount];
        int[] initialCache = new int[vCount];
        int[] bestCache = new int[vCount], secondCache = new int[vCount], versions = new int[vCount];
        Arrays.fill(bestCache, -1); Arrays.fill(secondCache, -1);
        PriorityQueue<Entry> queue = new PriorityQueue<>(ORDER);
        long candidateEvaluations = 0L, updates = 0L, disappearing = 0L;
        for (int v = 0; v < vCount; v++) {
            state.gains(v, gains, v * cCount);
            Entry entry = rank(v, state, gains, weight, bestCache, secondCache, ++versions[v]);
            if (entry != null) {
                initialBest[v] = entry.gain(); initialSecond[v] = entry.alternative();
                initialCache[v] = entry.cache();
                if (staticControl) entry = new Entry(v, entry.cache(), entry.gain(), 0L, entry.priority(), entry.version());
                queue.add(entry);
            } else initialCache[v] = -1;
            candidateEvaluations += cCount;
        }
        Integer[] sizeOrder = new Integer[vCount];
        for (int v = 0; v < vCount; v++) sizeOrder[v] = v;
        Arrays.sort(sizeOrder, Comparator.<Integer>comparingInt(v -> problem.videoSizes()[v]).thenComparingInt(v -> v));
        int[] feasiblePointer = new int[cCount];
        int pointer = vCount - 1;
        while (pointer >= 0 && problem.videoSizes()[sizeOrder[pointer]] > problem.cacheCapacity()) pointer--;
        Arrays.fill(feasiblePointer, pointer);
        int[] stamps = new int[vCount], dirty = new int[vCount];
        int epoch = 0;
        long indexNanos = System.nanoTime() - start;
        long rawObjective = 0L;
        List<Placement> trace = new ArrayList<>();
        while (true) {
            Entry selected = null;
            while (!queue.isEmpty()) {
                Entry entry = queue.poll();
                if (entry.version() == versions[entry.video()]) { selected = entry; break; }
            }
            if (verify) {
                Entry reference = exhaustive(problem, state.solution, weight, staticControl,
                        initialBest, initialSecond, initialCache);
                if ((selected == null) != (reference == null) || (selected != null
                        && (selected.video() != reference.video() || selected.cache() != reference.cache()
                        || selected.gain() != reference.gain() || selected.alternative() != reference.alternative()
                        || Double.compare(selected.priority(), reference.priority()) != 0))) {
                    throw new AssertionError("Dynamic queue/reference mismatch at step " + trace.size()
                            + ": " + selected + " versus " + reference);
                }
            }
            if (selected == null) break;
            int v = selected.video(), c = selected.cache();
            state.add(c, v);
            state.refresh(v);
            rawObjective += selected.gain();
            long fixedRegret = initialCache[v] == c ? initialBest[v] - initialSecond[v] : 0L;
            trace.add(new Placement(trace.size() + 1, c, v, selected.gain(),
                    selected.alternative(), staticControl ? fixedRegret : selected.gain() - selected.alternative(), fixedRegret,
                    selected.priority(), rawObjective, System.nanoTime() - start));
            if (verify && rawObjective != PlacementState.fullRaw(problem, state.solution)) {
                throw new AssertionError("Dynamic objective mismatch");
            }
            state.gains(v, gains, v * cCount);
            int count = 0;
            epoch++;
            stamps[v] = epoch; dirty[count++] = v;
            int remaining = problem.cacheCapacity() - state.used[c];
            while (feasiblePointer[c] >= 0 && problem.videoSizes()[sizeOrder[feasiblePointer[c]]] > remaining) {
                int affected = sizeOrder[feasiblePointer[c]--];
                if (gains[affected * cCount + c] > 0) disappearing++;
                if ((bestCache[affected] == c || secondCache[affected] == c) && stamps[affected] != epoch) {
                    stamps[affected] = epoch; dirty[count++] = affected;
                }
            }
            for (int i = 0; i < count; i++) {
                int affected = dirty[i];
                Entry entry;
                if (staticControl) {
                    entry = rankStatic(affected, state, gains, weight, initialBest, initialSecond,
                            initialCache, ++versions[affected]);
                    bestCache[affected] = entry == null ? -1 : entry.cache();
                    secondCache[affected] = -1;
                } else entry = rank(affected, state, gains, weight, bestCache, secondCache, ++versions[affected]);
                if (entry != null) queue.add(entry);
                updates++; candidateEvaluations += cCount;
            }
        }
        return new Result(state.solution, indexNanos, System.nanoTime() - start,
                updates, disappearing, candidateEvaluations, (long) gains.length * 8L,
                List.copyOf(trace));
    }

    private static Entry rankStatic(int v, PlacementState state, long[] gains, double weight,
            long[] initialBest, long[] initialSecond, int[] initialCache, int version) {
        Entry winner = null;
        for (int c = 0; c < state.caches; c++) {
            long gain = gains[v * state.caches + c];
            if (gain <= 0 || state.problem.videoSizes()[v] > state.problem.cacheCapacity() - state.used[c]) continue;
            long regret = c == initialCache[v] ? initialBest[v] - initialSecond[v] : 0L;
            Entry entry = new Entry(v, c, gain, 0,
                    (gain + weight * regret) / state.problem.videoSizes()[v], version);
            if (winner == null || ORDER.compare(entry, winner) < 0) winner = entry;
        }
        return winner;
    }

    private static Entry rank(int v, PlacementState state, long[] gains, double weight,
            int[] bestCaches, int[] secondCaches, int version) {
        long best = 0L, second = 0L;
        int bestCache = -1, secondCache = -1;
        for (int c = 0; c < state.caches; c++) {
            if (state.problem.videoSizes()[v] > state.problem.cacheCapacity() - state.used[c]) continue;
            long gain = gains[v * state.caches + c];
            if (gain > best) {
                second = best; secondCache = bestCache;
                best = gain; bestCache = c;
            } else if (gain > second) { second = gain; secondCache = c; }
        }
        bestCaches[v] = bestCache; secondCaches[v] = secondCache;
        if (bestCache < 0) return null;
        return new Entry(v, bestCache, best, second,
                (best + weight * (best - second)) / state.problem.videoSizes()[v], version);
    }

    /** Independent correctness reference: no cached gains, latencies, or priority queue. */
    private static Entry exhaustive(ProblemInstance p, Solution solution, double weight,
            boolean staticControl, long[] initialBest, long[] initialSecond, int[] initialCache) {
        int[] remaining = new int[p.cacheCount()];
        Arrays.fill(remaining, p.cacheCapacity());
        for (int c = 0; c < p.cacheCount(); c++) for (int v : solution.videosInCache(c)) remaining[c] -= p.videoSizes()[v];
        Entry winner = null;
        for (int v = 0; v < p.videoCount(); v++) {
            long[] gains = new long[p.cacheCount()];
            for (Endpoint endpoint : p.endpoints()) {
                RequestDemand request = endpoint.requestsByVideo().get(v);
                if (request == null) continue;
                int best = endpoint.dataCenterLatency();
                for (CacheConnection link : endpoint.connections()) if (solution.contains(link.cacheId(), v)) best = Math.min(best, link.latency());
                for (CacheConnection link : endpoint.connections()) gains[link.cacheId()] += request.requestCount()
                        * (long) Math.max(0, best - link.latency());
            }
            for (int c = 0; c < p.cacheCount(); c++) {
                if (p.videoSizes()[v] > remaining[c] || gains[c] <= 0) continue;
                long alternative = 0L;
                for (int other = 0; other < p.cacheCount(); other++) if (other != c && p.videoSizes()[v] <= remaining[other]) alternative = Math.max(alternative, gains[other]);
                long regret = staticControl ? (c == initialCache[v] ? initialBest[v] - initialSecond[v] : 0L)
                        : Math.max(0L, gains[c] - alternative);
                Entry entry = new Entry(v, c, gains[c], staticControl ? 0L : alternative,
                        (gains[c] + weight * regret) / p.videoSizes()[v], 0);
                if (winner == null || ORDER.compare(entry, winner) < 0) winner = entry;
            }
        }
        return winner;
    }

    private record Entry(int video, int cache, long gain, long alternative, double priority, int version) { }
    public record Placement(int step, int cache, int video, long gain, long alternative,
            long regret, long initialRegret, double priority, long rawObjective, long elapsedNanos) { }
    public record Result(Solution solution, long indexNanos, long totalNanos, long regretUpdates,
            long infeasibleAlternatives, long candidateEvaluations, long gainArrayBytes,
            List<Placement> trace) { }
}
