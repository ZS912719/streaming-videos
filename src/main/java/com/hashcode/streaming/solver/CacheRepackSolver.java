package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Exact 0/1 knapsack best response within a bounded candidate pool for one cache. */
public final class CacheRepackSolver {
    public record Move(int pass, int cache, long delta, long rawObjective, long elapsedNanos) { }
    public record Result(Solution solution, int passes, long evaluatedCells, long totalNanos,
            String termination, List<Move> trace) { }

    public Result improve(ProblemInstance p, Solution solution, int maxPasses,
            int absentCandidateLimit, boolean verify) {
        if (maxPasses < 1 || absentCandidateLimit < 1) throw new IllegalArgumentException("Positive repack limits required");
        long start = System.nanoTime();
        PlacementState state = new PlacementState(p, solution);
        long raw = state.raw(), cells = 0;
        int passes = 0;
        String termination = "pass_limit";
        List<Move> trace = new ArrayList<>();
        for (int pass = 1; pass <= maxPasses; pass++) {
            passes++;
            boolean changed = false;
            for (int c = 0; c < state.caches; c++) {
                long[] benefits = new long[state.videos];
                state.benefits(c, benefits);
                List<Integer> items = new ArrayList<>(solution.videosInCache(c));
                Collections.sort(items);
                long incumbent = 0;
                for (int v : items) incumbent += benefits[v];
                List<Integer> absent = new ArrayList<>();
                for (int v = 0; v < state.videos; v++) {
                    if (!state.has(c, v) && benefits[v] > 0 && p.videoSizes()[v] <= p.cacheCapacity()) absent.add(v);
                }
                absent.sort(Comparator.<Integer>comparingDouble(v -> (double) benefits[v] / p.videoSizes()[v])
                        .reversed().thenComparingInt(v -> v));
                items.addAll(absent.subList(0, Math.min(absentCandidateLimit, absent.size())));
                long[] dp = new long[p.cacheCapacity() + 1];
                BitSet[] take = new BitSet[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    int v = items.get(i), size = p.videoSizes()[v];
                    take[i] = new BitSet(dp.length);
                    for (int capacity = p.cacheCapacity(); capacity >= size; capacity--) {
                        long value = dp[capacity - size] + benefits[v];
                        cells++;
                        if (value > dp[capacity]) { dp[capacity] = value; take[i].set(capacity); }
                    }
                }
                long delta = dp[p.cacheCapacity()] - incumbent;
                if (delta <= 0) continue;
                BitSet selected = new BitSet(state.videos);
                int capacity = p.cacheCapacity();
                for (int i = items.size() - 1; i >= 0; i--) if (take[i].get(capacity)) {
                    int v = items.get(i); selected.set(v); capacity -= p.videoSizes()[v];
                }
                List<Integer> affected = new ArrayList<>();
                for (int v : new ArrayList<>(solution.videosInCache(c))) if (!selected.get(v)) {
                    state.remove(c, v); affected.add(v);
                }
                for (int v = selected.nextSetBit(0); v >= 0; v = selected.nextSetBit(v + 1)) if (!state.has(c, v)) {
                    state.add(c, v); affected.add(v);
                }
                for (int v : affected) state.refresh(v);
                raw += delta;
                if (verify && PlacementState.fullRaw(p, solution) != raw) throw new AssertionError("Repack delta mismatch");
                trace.add(new Move(pass, c, delta, raw, System.nanoTime() - start));
                changed = true;
            }
            if (!changed) { termination = "no_positive_candidate_pool_repack"; break; }
        }
        if (PlacementState.fullRaw(p, solution) != raw) throw new IllegalStateException("Final repack mismatch");
        return new Result(solution, passes, cells, System.nanoTime() - start, termination, List.copyOf(trace));
    }
}
