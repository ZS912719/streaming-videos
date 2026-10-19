package com.hashcode.streaming.experiments;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Read-only exact audit, independent of the solver's latency index and loss table. */
public final class NeighborhoodAudit {
    private NeighborhoodAudit() { }

    public record Result(long maximumGain, int cacheId, int addedVideoId,
            int removedVideoId, int secondRemovedVideoId, int improvingCaches) { }

    private record Removal(long loss, int first, int second) { }

    public static Result audit(ProblemInstance problem, Solution solution, int removalLimit) {
        if (removalLimit != 1 && removalLimit != 2) {
            throw new IllegalArgumentException("Removal limit must be one or two");
        }
        long maximum = 0;
        int bestCache = -1, bestAdded = -1, bestFirst = -1, bestSecond = -1, improving = 0;
        // Freshly derive contributions from the actual final placement. Two scans
        // per demand avoid quadratic work in endpoint degree on dense inputs.
        long[][] contributions = new long[problem.cacheCount()][problem.videoCount()];
        for (var endpoint : problem.endpoints()) {
            for (var request : endpoint.requestsByVideo().values()) {
                int best = endpoint.dataCenterLatency(), second = best, serving = -1;
                for (var link : endpoint.connections()) {
                    if (!solution.contains(link.cacheId(), request.videoId())) continue;
                    if (link.latency() < best) {
                        second = best;
                        best = link.latency();
                        serving = link.cacheId();
                    } else if (link.latency() < second) {
                        second = link.latency();
                    }
                }
                for (var link : endpoint.connections()) {
                    int without = link.cacheId() == serving ? second : best;
                    contributions[link.cacheId()][request.videoId()] += request.requestCount()
                            * (long) Math.max(0, without - link.latency());
                }
            }
        }
        for (int cache = 0; cache < problem.cacheCount(); cache++) {
            long[] contribution = contributions[cache];
            var stored = new ArrayList<>(solution.videosInCache(cache));
            stored.sort(Comparator.<Integer>comparingInt(v -> problem.videoSizes()[v])
                    .thenComparingInt(Integer::intValue));
            int count = stored.size();
            int[] videos = new int[count], sizes = new int[count];
            int remaining = problem.cacheCapacity();
            for (int i = 0; i < count; i++) {
                videos[i] = stored.get(i);
                sizes[i] = problem.videoSizes()[videos[i]];
                remaining -= sizes[i];
            }
            // Keep the two cheapest distinct videos in every size-sorted suffix.
            // A pair query can then exclude its first member without approximation.
            int[] cheapest = new int[count + 1], runnerUp = new int[count + 1];
            Arrays.fill(cheapest, -1);
            Arrays.fill(runnerUp, -1);
            for (int i = count - 1; i >= 0; i--) {
                int a = cheapest[i + 1], b = runnerUp[i + 1];
                if (a < 0 || contribution[videos[i]] < contribution[videos[a]]) {
                    cheapest[i] = i;
                    runnerUp[i] = a;
                } else {
                    cheapest[i] = a;
                    runnerUp[i] = b < 0 || contribution[videos[i]] < contribution[videos[b]] ? i : b;
                }
            }
            Map<Integer, Removal> memo = new HashMap<>();
            boolean cacheImproves = false;
            for (int added = 0; added < problem.videoCount(); added++) {
                if (solution.contains(cache, added) || contribution[added] <= 0) continue;
                int needed = problem.videoSizes()[added] - remaining;
                Removal removal;
                if (needed <= 0) {
                    removal = new Removal(0, -1, -1);
                } else {
                    removal = memo.get(needed);
                    if (removal == null) {
                        int single = cheapest[lowerBound(sizes, needed)];
                        removal = single < 0 ? new Removal(Long.MAX_VALUE, -1, -1)
                                : new Removal(contribution[videos[single]], videos[single], -1);
                        if (removalLimit == 2) {
                            for (int i = 0; i < count; i++) {
                                int position = lowerBound(sizes, needed - sizes[i]);
                                int other = cheapest[position];
                                if (other == i) other = runnerUp[position];
                                if (other < 0) continue;
                                long loss = contribution[videos[i]] + contribution[videos[other]];
                                if (loss < removal.loss()) {
                                    removal = new Removal(loss, videos[i], videos[other]);
                                }
                            }
                        }
                        memo.put(needed, removal);
                    }
                }
                if (removal.loss() == Long.MAX_VALUE) continue;
                long gain = contribution[added] - removal.loss();
                if (gain > 0) cacheImproves = true;
                if (gain > maximum) {
                    maximum = gain;
                    bestCache = cache;
                    bestAdded = added;
                    bestFirst = removal.first();
                    bestSecond = removal.second();
                }
            }
            if (cacheImproves) improving++;
        }
        return new Result(maximum, bestCache, bestAdded, bestFirst, bestSecond, improving);
    }

    private static int lowerBound(int[] sorted, int target) {
        int low = 0, high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < target) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
