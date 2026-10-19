package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Targeted two-for-one and one-for-two replacements with exact additive video benefits. */
public final class PackingSolver {
    public record Move(int pass, int cache, int removeA, int removeB, int addA, int addB,
            long delta, long rawObjective, long elapsedNanos) { }
    public record Result(Solution solution, int passes, long evaluated, long totalNanos,
            String termination, List<Move> trace) { }

    public Result improve(ProblemInstance p, Solution solution, int maxPasses,
            int candidateLimit, String neighborhood, boolean verify) {
        if (maxPasses < 1 || candidateLimit < 2 || !List.of("both", "two-for-one", "one-for-two").contains(neighborhood))
            throw new IllegalArgumentException("Invalid packing settings");
        long start = System.nanoTime();
        PlacementState state = new PlacementState(p, solution);
        long raw = state.raw(), evaluated = 0;
        List<Move> trace = new ArrayList<>();
        int passes = 0;
        String termination = "pass_limit";
        final long infinity = Long.MAX_VALUE / 4;
        for (int pass = 1; pass <= maxPasses; pass++) {
            passes++;
            boolean changed = false;
            for (int c = 0; c < state.caches; c++) {
                long[] benefits = new long[state.videos];
                state.benefits(c, benefits);
                int remaining = p.cacheCapacity() - state.used[c];
                List<Integer> stored = new ArrayList<>(solution.videosInCache(c));
                Collections.sort(stored);
                int capacity = p.cacheCapacity();
                long[] singleLoss = new long[capacity + 1], pairLoss = new long[capacity + 1];
                int[] single = new int[capacity + 1], pairA = new int[capacity + 1], pairB = new int[capacity + 1];
                Arrays.fill(singleLoss, infinity); Arrays.fill(pairLoss, infinity);
                Arrays.fill(single, -1); Arrays.fill(pairA, -1); Arrays.fill(pairB, -1);
                for (int i = 0; i < stored.size(); i++) {
                    int a = stored.get(i), size = p.videoSizes()[a];
                    if (benefits[a] < singleLoss[size]) { singleLoss[size] = benefits[a]; single[size] = a; }
                    for (int j = i + 1; j < stored.size(); j++) {
                        int b = stored.get(j), combined = size + p.videoSizes()[b];
                        long loss = benefits[a] + benefits[b];
                        if (loss < pairLoss[combined]) {
                            pairLoss[combined] = loss; pairA[combined] = a; pairB[combined] = b;
                        }
                    }
                }
                for (int size = capacity - 1; size >= 0; size--) {
                    if (singleLoss[size + 1] < singleLoss[size]) {
                        singleLoss[size] = singleLoss[size + 1]; single[size] = single[size + 1];
                    }
                    if (pairLoss[size + 1] < pairLoss[size]) {
                        pairLoss[size] = pairLoss[size + 1]; pairA[size] = pairA[size + 1]; pairB[size] = pairB[size + 1];
                    }
                }
                long bestDelta = 0;
                int removeA = -1, removeB = -1, addA = -1, addB = -1;
                List<Integer> absent = new ArrayList<>();
                for (int v = 0; v < state.videos; v++) if (!state.has(c, v) && benefits[v] > 0 && p.videoSizes()[v] <= capacity) {
                    absent.add(v);
                    if (!neighborhood.equals("one-for-two")) {
                        int need = Math.max(0, p.videoSizes()[v] - remaining);
                        evaluated++;
                        if (need > 0 && pairLoss[need] < infinity && benefits[v] - pairLoss[need] > bestDelta) {
                            bestDelta = benefits[v] - pairLoss[need];
                            removeA = pairA[need]; removeB = pairB[need]; addA = v; addB = -1;
                        }
                    }
                }
                if (!neighborhood.equals("two-for-one")) {
                    absent.sort(Comparator.<Integer>comparingDouble(v -> (double) benefits[v] / p.videoSizes()[v])
                            .reversed().thenComparingInt(v -> v));
                    int count = Math.min(candidateLimit, absent.size());
                    for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
                        int a = absent.get(i), b = absent.get(j);
                        int need = Math.max(0, p.videoSizes()[a] + p.videoSizes()[b] - remaining);
                        evaluated++;
                        if (need > capacity || (need > 0 && singleLoss[need] == infinity)) continue;
                        long delta = benefits[a] + benefits[b] - (need == 0 ? 0 : singleLoss[need]);
                        if (delta > bestDelta) {
                            bestDelta = delta; removeA = need == 0 ? -1 : single[need];
                            removeB = -1; addA = a; addB = b;
                        }
                    }
                }
                if (addA >= 0) {
                    if (removeA >= 0) state.remove(c, removeA);
                    if (removeB >= 0) state.remove(c, removeB);
                    state.add(c, addA);
                    if (addB >= 0) state.add(c, addB);
                    for (int v : new int[]{removeA, removeB, addA, addB}) if (v >= 0) state.refresh(v);
                    raw += bestDelta;
                    if (verify && PlacementState.fullRaw(p, solution) != raw) throw new AssertionError("Packing delta mismatch");
                    trace.add(new Move(pass, c, removeA, removeB, addA, addB, bestDelta, raw, System.nanoTime() - start));
                    changed = true;
                }
            }
            if (!changed) { termination = "no_positive_scanned_packing_move"; break; }
        }
        if (PlacementState.fullRaw(p, solution) != raw) throw new IllegalStateException("Final packing objective mismatch");
        return new Result(solution, passes, evaluated, System.nanoTime() - start, termination, List.copyOf(trace));
    }
}
