package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Cooperative, checkpoint-safe form of the existing exact indexed constructor. */
final class BudgetedConstruction {
    private record Entry(int video, int cache, long gain, double priority, int version) { }
    private static final Comparator<Entry> ORDER = Comparator.comparingDouble(Entry::priority).reversed()
            .thenComparing(Comparator.comparingLong(Entry::gain).reversed())
            .thenComparingInt(Entry::video).thenComparingInt(Entry::cache);

    static PlacementState construct(ProblemInstance p, String kind, long deadline) {
        PlacementState state = new PlacementState(p, new Solution(p.cacheCount()));
        int vCount = state.videos, cCount = state.caches;
        long[] gains = new long[Math.multiplyExact(vCount, cCount)];
        long[] initialRegret = new long[vCount];
        int[] initialCache = new int[vCount], bestCache = new int[vCount], secondCache = new int[vCount];
        int[] versions = new int[vCount];
        Arrays.fill(bestCache, -1); Arrays.fill(secondCache, -1);
        PriorityQueue<Entry> queue = new PriorityQueue<>(ORDER);
        for (int v = 0; v < vCount; v++) {
            if (System.nanoTime() >= deadline) return state;
            state.gains(v, gains, v*cCount);
            Entry entry = rank(v, state, gains, kind, bestCache, secondCache,
                    initialCache, initialRegret, ++versions[v], true);
            if (entry != null) queue.add(entry);
        }
        Integer[] sizeOrder = new Integer[vCount];
        for (int v = 0; v < vCount; v++) sizeOrder[v] = v;
        Arrays.sort(sizeOrder, Comparator.<Integer>comparingInt(v -> p.videoSizes()[v]).thenComparingInt(v -> v));
        int pointer = vCount-1;
        while (pointer >= 0 && p.videoSizes()[sizeOrder[pointer]] > p.cacheCapacity()) pointer--;
        int[] feasiblePointer = new int[cCount], stamps = new int[vCount], dirty = new int[vCount];
        Arrays.fill(feasiblePointer, pointer);
        int epoch = 0;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            Entry selected = queue.poll();
            if (selected.version != versions[selected.video]) continue;
            int v = selected.video, c = selected.cache;
            state.add(c, v); state.refresh(v);
            state.gains(v, gains, v*cCount);
            int count = 0;
            epoch++;
            stamps[v] = epoch; dirty[count++] = v;
            int remaining = p.cacheCapacity()-state.used[c];
            while (feasiblePointer[c] >= 0 && p.videoSizes()[sizeOrder[feasiblePointer[c]]] > remaining) {
                int affected = sizeOrder[feasiblePointer[c]--];
                if ((bestCache[affected] == c || secondCache[affected] == c) && stamps[affected] != epoch) {
                    stamps[affected] = epoch; dirty[count++] = affected;
                }
            }
            // Finish every invalidation before consulting the clock: priorities never become stale.
            for (int i = 0; i < count; i++) {
                int affected = dirty[i];
                Entry entry = rank(affected, state, gains, kind, bestCache, secondCache,
                        initialCache, initialRegret, ++versions[affected], false);
                if (entry != null) queue.add(entry);
            }
        }
        return state;
    }

    private static Entry rank(int v, PlacementState state, long[] gains, String kind,
            int[] bestCache, int[] secondCache, int[] initialCache, long[] initialRegret,
            int version, boolean initialize) {
        long best = 0, second = 0;
        int cache = -1, alternative = -1;
        for (int c = 0; c < state.caches; c++) {
            if (state.problem.videoSizes()[v] > state.problem.cacheCapacity()-state.used[c]) continue;
            long gain = gains[v*state.caches+c];
            if (gain > best) { second=best; alternative=cache; best=gain; cache=c; }
            else if (gain > second) { second=gain; alternative=c; }
        }
        if (initialize) { initialCache[v]=cache; initialRegret[v]=best-second; }
        bestCache[v]=cache; secondCache[v]=alternative;
        if (cache < 0) return null;
        if (kind.equals("static")) {
            Entry winner = null;
            for (int c = 0; c < state.caches; c++) {
                long gain = gains[v*state.caches+c];
                if (gain <= 0 || state.problem.videoSizes()[v] > state.problem.cacheCapacity()-state.used[c]) continue;
                Entry candidate = new Entry(v,c,gain,(gain+(double)(c==initialCache[v]?initialRegret[v]:0))
                        /state.problem.videoSizes()[v],version);
                if (winner==null || ORDER.compare(candidate,winner)<0) winner=candidate;
            }
            bestCache[v]=winner==null?-1:winner.cache;
            secondCache[v]=-1;
            return winner;
        }
        double weight = kind.equals("zero") ? 0 : 1;
        return new Entry(v,cache,best,(best+weight*(best-second))/state.problem.videoSizes()[v],version);
    }
}
