package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Exact two-cache exchange using cached per-video transfer deltas. */
public final class IndexedExchangeSolver {
    public record Settings(int pairLimit, int videosPerCache, int maxRounds,
            boolean firstImprovement, long timeLimitMillis, boolean verify) {
        public Settings {
            if (pairLimit < 1 || videosPerCache < 1 || maxRounds < 1 || timeLimitMillis < 0)
                throw new IllegalArgumentException("Invalid exchange limits");
        }
    }

    public Result improve(ProblemInstance problem, Solution solution, Settings settings) {
        long start = System.nanoTime();
        PlacementState state = new PlacementState(problem, solution);
        int cCount = state.caches, vCount = state.videos;
        int slots = 0;
        for (int c = 0; c < cCount; c++) slots += solution.videosInCache(c).size();
        int[] slotVideo = new int[slots], slotCache = new int[slots];
        int[][] cacheSlots = new int[cCount][];
        int[] slotByPair = new int[Math.multiplyExact(cCount, vCount)];
        Arrays.fill(slotByPair, -1);
        long[] transfer = new long[Math.multiplyExact(slots, cCount)];
        long[] popularity = new long[vCount];
        for (int v = 0; v < vCount; v++) for (PlacementState.Demand demand : state.demands[v]) popularity[v] += demand.count;
        int slot = 0;
        for (int c = 0; c < cCount; c++) {
            cacheSlots[c] = new int[solution.videosInCache(c).size()];
            int i = 0;
            for (int v : solution.videosInCache(c)) {
                cacheSlots[c][i++] = slot;
                slotVideo[slot] = v; slotCache[slot] = c;
                slotByPair[c * vCount + v] = slot;
                state.transfers(v, c, transfer, slot * cCount);
                slot++;
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (int a = 0; a < cCount; a++) for (int b = a + 1; b < cCount; b++) pairs.add(new Pair(a, b));
        Collections.shuffle(pairs, new Random(20_170_317L));
        int pairCount = Math.min(settings.pairLimit(), pairs.size());
        long indexNanos = System.nanoTime() - start;
        long raw = state.raw(), evaluated = 0, feasible = 0, positive = 0, scanNanos = 0, updateNanos = 0;
        int rounds = 0, roundedImprovements = 0;
        String termination = "round_limit";
        List<Move> trace = new ArrayList<>();
        long deadline = settings.timeLimitMillis() == 0 ? Long.MAX_VALUE : start + settings.timeLimitMillis() * 1_000_000L;
        for (int round = 1; round <= settings.maxRounds(); round++) {
            rounds++;
            int[][] selected = new int[cCount][];
            for (int c = 0; c < cCount; c++) {
                Integer[] ordered = Arrays.stream(cacheSlots[c]).boxed().toArray(Integer[]::new);
                Arrays.sort(ordered, Comparator.<Integer>comparingLong(s -> popularity[slotVideo[s]])
                        .reversed().thenComparingInt(s -> slotVideo[s]));
                selected[c] = new int[Math.min(settings.videosPerCache(), ordered.length)];
                for (int i = 0; i < selected[c].length; i++) selected[c][i] = ordered[i];
            }
            long scanStart = System.nanoTime();
            long bestDelta = 0;
            int bestA = -1, bestB = -1;
            boolean timedOut = false;
            scan:
            for (int pairIndex = 0; pairIndex < pairCount; pairIndex++) {
                if (System.nanoTime() >= deadline) { timedOut = true; break; }
                Pair pair = pairs.get(pairIndex);
                int a = pair.a(), b = pair.b();
                for (int sa : selected[a]) for (int sb : selected[b]) {
                    evaluated++;
                    int va = slotVideo[sa], vb = slotVideo[sb];
                    int sizeA = problem.videoSizes()[va], sizeB = problem.videoSizes()[vb];
                    if (va == vb || state.has(a, vb) || state.has(b, va)
                            || state.used[a] - sizeA + sizeB > problem.cacheCapacity()
                            || state.used[b] - sizeB + sizeA > problem.cacheCapacity()) continue;
                    feasible++;
                    long delta = transfer[sa * cCount + b] + transfer[sb * cCount + a];
                    if (settings.verify()) {
                        state.remove(a, va); state.remove(b, vb); state.add(a, vb); state.add(b, va);
                        long actual = PlacementState.fullRaw(problem, solution) - raw;
                        state.remove(a, vb); state.remove(b, va); state.add(a, va); state.add(b, vb);
                        if (actual != delta) throw new AssertionError("Exchange delta " + delta + " != " + actual);
                    }
                    if (delta > 0) positive++;
                    if (delta > bestDelta) {
                        bestDelta = delta; bestA = sa; bestB = sb;
                        if (settings.firstImprovement()) break scan;
                    }
                }
            }
            scanNanos += System.nanoTime() - scanStart;
            if (bestA < 0) { termination = timedOut ? "time_budget" : "no_positive_scanned_exchange"; break; }
            long updateStart = System.nanoTime();
            int a = slotCache[bestA], b = slotCache[bestB];
            int va = slotVideo[bestA], vb = slotVideo[bestB];
            state.remove(a, va); state.remove(b, vb); state.add(a, vb); state.add(b, va);
            slotByPair[a * vCount + va] = -1; slotByPair[b * vCount + vb] = -1;
            slotByPair[a * vCount + vb] = bestA; slotByPair[b * vCount + va] = bestB;
            slotVideo[bestA] = vb; slotVideo[bestB] = va;
            state.refresh(va); state.refresh(vb);
            for (int v : new int[]{va, vb}) for (int c = 0; c < cCount; c++) {
                int s = slotByPair[c * vCount + v];
                if (s >= 0) state.transfers(v, c, transfer, s * cCount);
            }
            long beforeScore = score(problem, raw);
            raw += bestDelta;
            if (score(problem, raw) > beforeScore) roundedImprovements++;
            if (settings.verify() && PlacementState.fullRaw(problem, solution) != raw) throw new AssertionError("Exchange state mismatch");
            updateNanos += System.nanoTime() - updateStart;
            trace.add(new Move(round, a, va, b, vb, bestDelta, raw, System.nanoTime() - start));
            if (timedOut) { termination = "time_budget"; break; }
        }
        if (PlacementState.fullRaw(problem, solution) != raw) throw new IllegalStateException("Final exchange objective mismatch");
        return new Result(solution, rounds, trace.size(), evaluated, feasible, positive, roundedImprovements,
                indexNanos, scanNanos, updateNanos, System.nanoTime() - start, termination,
                (long) transfer.length * 8 + (long) slotByPair.length * 4, List.copyOf(trace));
    }

    private static long score(ProblemInstance p, long raw) { return p.totalRequestCount() == 0 ? 0 : raw * 1000L / p.totalRequestCount(); }
    private record Pair(int a, int b) { }
    public record Move(int round, int cacheA, int videoA, int cacheB, int videoB,
            long delta, long rawObjective, long elapsedNanos) { }
    public record Result(Solution solution, int rounds, int accepted, long evaluated, long feasible,
            long positive, int roundedImprovements, long indexNanos, long scanNanos,
            long updateNanos, long totalNanos, String termination, long indexArrayBytes, List<Move> trace) { }
}
