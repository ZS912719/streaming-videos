package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Greedy construction augmented with a fixed alternative-placement regret. */
public final class OpportunityCostGreedySolver {
    public Solution solve(ProblemInstance problem) {
        return solveWithStatistics(problem).solution();
    }

    public ConstructionResult solveWithStatistics(ProblemInstance problem) {
        long startNanos = System.nanoTime();
        SparseCandidateIndex index = buildSparseIndex(problem);
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble(QueueEntry::adjustedDensity).reversed()
                        .thenComparing(Comparator.comparingLong(QueueEntry::gain).reversed()));

        for (int candidateId = 0; candidateId < index.candidateCount(); candidateId++) {
            int videoId = index.videoIds()[candidateId];
            int size = problem.videoSizes()[videoId];
            long gain = index.initialGains()[candidateId];
            if (size <= problem.cacheCapacity() && gain > 0) {
                queue.add(QueueEntry.of(candidateId, gain,
                        index.staticRegrets()[candidateId], size));
            }
        }

        Solution solution = new Solution(problem.cacheCount());
        int[] remainingCapacity = new int[problem.cacheCount()];
        java.util.Arrays.fill(remainingCapacity, problem.cacheCapacity());
        List<PlacementTrace> trace = new ArrayList<>();
        int regretPlacements = 0;
        long selectedStaticRegret = 0L;

        while (!queue.isEmpty()) {
            QueueEntry entry = queue.poll();
            int candidateId = entry.candidateId();
            int cacheId = index.cacheIds()[candidateId];
            int videoId = index.videoIds()[candidateId];
            int size = problem.videoSizes()[videoId];
            if (size > remainingCapacity[cacheId] || solution.contains(cacheId, videoId)) {
                continue;
            }

            long currentGain = marginalGain(candidateId, index);
            if (currentGain <= 0) continue;
            long regret = index.staticRegrets()[candidateId];
            QueueEntry refreshed = QueueEntry.of(candidateId, currentGain, regret, size);

            // The current gain can only decrease, while the static regret is fixed.
            if (!queue.isEmpty()
                    && refreshed.adjustedDensity() + 1e-12 < queue.peek().adjustedDensity()) {
                queue.add(refreshed);
                continue;
            }

            solution.addVideo(cacheId, videoId);
            remainingCapacity[cacheId] -= size;
            applyPlacement(candidateId, index);
            if (regret > 0) regretPlacements++;
            selectedStaticRegret += regret;
            trace.add(new PlacementTrace(trace.size() + 1, cacheId, videoId, size,
                    currentGain, regret, currentGain + regret,
                    remainingCapacity[cacheId]));
        }

        ConstructionStatistics statistics = new ConstructionStatistics(
                trace.size(), regretPlacements, selectedStaticRegret,
                System.nanoTime() - startNanos, List.copyOf(trace));
        return new ConstructionResult(solution, statistics);
    }

    /**
     * The fixed regret is the initial gain of a candidate minus the best initial gain for
     * the same video in another cache. Only a uniquely best placement has positive regret.
     */
    private static long[] staticRegrets(int[] videoIds, long[] initialGains, int videoCount) {
        long[] best = new long[videoCount];
        long[] secondBest = new long[videoCount];
        int[] bestCandidate = new int[videoCount];
        java.util.Arrays.fill(bestCandidate, -1);
        for (int candidateId = 0; candidateId < videoIds.length; candidateId++) {
            int videoId = videoIds[candidateId];
            long gain = initialGains[candidateId];
            if (gain > best[videoId]) {
                secondBest[videoId] = best[videoId];
                best[videoId] = gain;
                bestCandidate[videoId] = candidateId;
            } else if (gain > secondBest[videoId]) {
                secondBest[videoId] = gain;
            }
        }
        long[] regrets = new long[videoIds.length];
        for (int videoId = 0; videoId < videoCount; videoId++) {
            int candidateId = bestCandidate[videoId];
            if (candidateId >= 0) {
                regrets[candidateId] = Math.max(0L, best[videoId] - secondBest[videoId]);
            }
        }
        return regrets;
    }

    private static SparseCandidateIndex buildSparseIndex(ProblemInstance problem) {
        List<DemandView> demands = enumerateDemands(problem);
        long pairCountLong = (long) problem.cacheCount() * problem.videoCount();
        if (pairCountLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too many cache-video pairs: " + pairCountLong);
        }
        int pairCount = (int) pairCountLong;
        long[] gainByPair = new long[pairCount];
        int[] contributionCountByPair = new int[pairCount];
        int[] encounterOrder = new int[pairCount];
        int candidateCount = 0;
        long contributionTotal = 0L;

        for (DemandView demand : demands) {
            for (CacheConnection connection : demand.endpoint().connections()) {
                int pairId = pairId(connection.cacheId(), demand.request().videoId(),
                        problem.videoCount());
                if (contributionCountByPair[pairId] == 0) {
                    encounterOrder[candidateCount++] = pairId;
                }
                int savedLatency = Math.max(0,
                        demand.endpoint().dataCenterLatency() - connection.latency());
                gainByPair[pairId] += demand.request().requestCount() * (long) savedLatency;
                contributionCountByPair[pairId]++;
                contributionTotal++;
            }
        }
        if (contributionTotal > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Sparse candidate index is too large: " + contributionTotal + " contributions");
        }

        int[] orderedPairs = legacyCandidateOrder(
                encounterOrder, candidateCount, problem.videoCount());
        int[] cacheIds = new int[candidateCount];
        int[] videoIds = new int[candidateCount];
        long[] initialGains = new long[candidateCount];
        int[] offsets = new int[candidateCount + 1];
        int[] candidateIdByPair = new int[pairCount];
        java.util.Arrays.fill(candidateIdByPair, -1);
        for (int candidateId = 0; candidateId < candidateCount; candidateId++) {
            int pairId = orderedPairs[candidateId];
            candidateIdByPair[pairId] = candidateId;
            cacheIds[candidateId] = pairId / problem.videoCount();
            videoIds[candidateId] = pairId % problem.videoCount();
            initialGains[candidateId] = gainByPair[pairId];
            offsets[candidateId + 1] = offsets[candidateId]
                    + contributionCountByPair[pairId];
        }

        int[] demandIds = new int[(int) contributionTotal];
        short[] cacheLatencies = new short[(int) contributionTotal];
        int[] writePositions = offsets.clone();
        for (DemandView demand : demands) {
            for (CacheConnection connection : demand.endpoint().connections()) {
                int pairId = pairId(connection.cacheId(), demand.request().videoId(),
                        problem.videoCount());
                int candidateId = candidateIdByPair[pairId];
                int position = writePositions[candidateId]++;
                demandIds[position] = demand.demandId();
                cacheLatencies[position] = (short) connection.latency();
            }
        }

        int[] bestLatencies = new int[demands.size()];
        long[] requestCounts = new long[demands.size()];
        for (DemandView demand : demands) {
            bestLatencies[demand.demandId()] = demand.endpoint().dataCenterLatency();
            requestCounts[demand.demandId()] = demand.request().requestCount();
        }
        long[] regrets = staticRegrets(videoIds, initialGains, problem.videoCount());
        return new SparseCandidateIndex(cacheIds, videoIds, initialGains, regrets, offsets,
                demandIds, cacheLatencies, bestLatencies, requestCounts);
    }

    private static List<DemandView> enumerateDemands(ProblemInstance problem) {
        List<DemandView> demands = new ArrayList<>();
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                demands.add(new DemandView(demands.size(), endpoint, request));
            }
        }
        return demands;
    }

    private static long marginalGain(int candidateId, SparseCandidateIndex index) {
        long gain = 0L;
        int start = index.offsets()[candidateId];
        int end = index.offsets()[candidateId + 1];
        for (int position = start; position < end; position++) {
            int demandId = index.demandIds()[position];
            int cacheLatency = Short.toUnsignedInt(index.cacheLatencies()[position]);
            int currentBest = index.bestLatencies()[demandId];
            if (cacheLatency < currentBest) {
                gain += index.requestCounts()[demandId] * (long) (currentBest - cacheLatency);
            }
        }
        return gain;
    }

    private static void applyPlacement(int candidateId, SparseCandidateIndex index) {
        int start = index.offsets()[candidateId];
        int end = index.offsets()[candidateId + 1];
        for (int position = start; position < end; position++) {
            int demandId = index.demandIds()[position];
            int cacheLatency = Short.toUnsignedInt(index.cacheLatencies()[position]);
            if (cacheLatency < index.bestLatencies()[demandId]) {
                index.bestLatencies()[demandId] = cacheLatency;
            }
        }
    }

    private static int pairId(int cacheId, int videoId, int videoCount) {
        return cacheId * videoCount + videoId;
    }

    private static int[] legacyCandidateOrder(
            int[] encounterOrder, int candidateCount, int videoCount) {
        Map<Long, Boolean> legacyOrder = new HashMap<>();
        for (int i = 0; i < candidateCount; i++) {
            int pairId = encounterOrder[i];
            int cacheId = pairId / videoCount;
            int videoId = pairId % videoCount;
            legacyOrder.computeIfAbsent(pack(cacheId, videoId), ignored -> Boolean.TRUE);
        }
        int[] orderedPairs = new int[candidateCount];
        int position = 0;
        for (long key : legacyOrder.keySet()) {
            int cacheId = (int) (key >>> 32);
            int videoId = (int) key;
            orderedPairs[position++] = pairId(cacheId, videoId, videoCount);
        }
        return orderedPairs;
    }

    private static long pack(int high, int low) {
        return ((long) high << 32) | (low & 0xffffffffL);
    }

    private record DemandView(int demandId, Endpoint endpoint, RequestDemand request) { }

    private record SparseCandidateIndex(
            int[] cacheIds,
            int[] videoIds,
            long[] initialGains,
            long[] staticRegrets,
            int[] offsets,
            int[] demandIds,
            short[] cacheLatencies,
            int[] bestLatencies,
            long[] requestCounts) {
        private int candidateCount() { return cacheIds.length; }
    }

    private record QueueEntry(int candidateId, long gain, double adjustedDensity) {
        private static QueueEntry of(int candidateId, long gain, long regret, int size) {
            return new QueueEntry(candidateId, gain, (double) (gain + regret) / size);
        }
    }

    public record PlacementTrace(
            int placement,
            int cacheId,
            int videoId,
            int videoSize,
            long marginalGain,
            long staticRegret,
            long adjustedValue,
            int remainingCapacity) { }

    public record ConstructionStatistics(
            int placements,
            int regretPlacements,
            long selectedStaticRegret,
            long elapsedNanos,
            List<PlacementTrace> trace) { }

    public record ConstructionResult(Solution solution, ConstructionStatistics statistics) { }
}
