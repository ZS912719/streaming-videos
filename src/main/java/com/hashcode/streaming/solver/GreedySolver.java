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

/** Dynamic marginal-gain-per-megabyte greedy solver with a compact sparse index. */
public final class GreedySolver {
    public Solution solve(ProblemInstance problem) {
        SparseCandidateIndex index = buildSparseIndex(problem);
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble(QueueEntry::density).reversed()
                        .thenComparing(Comparator.comparingLong(QueueEntry::gain).reversed()));

        for (int candidateId = 0; candidateId < index.candidateCount(); candidateId++) {
            int videoId = index.videoIds()[candidateId];
            int size = problem.videoSizes()[videoId];
            long gain = index.initialGains()[candidateId];
            if (size <= problem.cacheCapacity() && gain > 0) {
                queue.add(QueueEntry.of(candidateId, gain, size));
            }
        }

        Solution solution = new Solution(problem.cacheCount());
        int[] remainingCapacity = new int[problem.cacheCount()];
        java.util.Arrays.fill(remainingCapacity, problem.cacheCapacity());

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
            if (currentGain <= 0) {
                continue;
            }
            QueueEntry refreshed = QueueEntry.of(candidateId, currentGain, size);

            // Stored values are upper bounds because placements only reduce marginal gains.
            if (!queue.isEmpty() && refreshed.density() + 1e-12 < queue.peek().density()) {
                queue.add(refreshed);
                continue;
            }

            solution.addVideo(cacheId, videoId);
            remainingCapacity[cacheId] -= size;
            applyPlacement(candidateId, index);
        }
        return solution;
    }

    /**
     * Builds a CSR-like index in two passes. Each candidate stores a contiguous slice of only
     * the requests it can improve. Parallel primitive arrays avoid one Java object per edge.
     */
    private static SparseCandidateIndex buildSparseIndex(ProblemInstance problem) {
        List<DemandView> demands = enumerateDemands(problem);
        Map<Long, CandidateBuilder> builders = new HashMap<>();
        long contributionTotal = 0L;

        for (DemandView demand : demands) {
            for (CacheConnection connection : demand.endpoint().connections()) {
                long key = pack(connection.cacheId(), demand.request().videoId());
                CandidateBuilder builder = builders.computeIfAbsent(key,
                        ignored -> new CandidateBuilder(connection.cacheId(), demand.request().videoId()));
                builder.initialGain += demand.request().requestCount()
                        * (long) (demand.endpoint().dataCenterLatency() - connection.latency());
                builder.contributionCount++;
                contributionTotal++;
            }
        }
        if (contributionTotal > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Sparse candidate index is too large: " + contributionTotal + " contributions");
        }

        int candidateCount = builders.size();
        int[] cacheIds = new int[candidateCount];
        int[] videoIds = new int[candidateCount];
        long[] initialGains = new long[candidateCount];
        int[] offsets = new int[candidateCount + 1];
        int candidateId = 0;
        for (CandidateBuilder builder : builders.values()) {
            builder.candidateId = candidateId;
            cacheIds[candidateId] = builder.cacheId;
            videoIds[candidateId] = builder.videoId;
            initialGains[candidateId] = builder.initialGain;
            offsets[candidateId + 1] = offsets[candidateId] + builder.contributionCount;
            candidateId++;
        }

        int[] demandIds = new int[(int) contributionTotal];
        short[] cacheLatencies = new short[(int) contributionTotal];
        int[] writePositions = offsets.clone();
        for (DemandView demand : demands) {
            for (CacheConnection connection : demand.endpoint().connections()) {
                CandidateBuilder builder = builders.get(
                        pack(connection.cacheId(), demand.request().videoId()));
                int position = writePositions[builder.candidateId]++;
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

        return new SparseCandidateIndex(cacheIds, videoIds, initialGains, offsets,
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

    private static long pack(int high, int low) {
        return ((long) high << 32) | (low & 0xffffffffL);
    }

    private static final class CandidateBuilder {
        private final int cacheId;
        private final int videoId;
        private long initialGain;
        private int contributionCount;
        private int candidateId;

        private CandidateBuilder(int cacheId, int videoId) {
            this.cacheId = cacheId;
            this.videoId = videoId;
        }
    }

    private record DemandView(int demandId, Endpoint endpoint, RequestDemand request) { }

    private record SparseCandidateIndex(
            int[] cacheIds,
            int[] videoIds,
            long[] initialGains,
            int[] offsets,
            int[] demandIds,
            short[] cacheLatencies,
            int[] bestLatencies,
            long[] requestCounts) {
        private int candidateCount() {
            return cacheIds.length;
        }
    }

    private record QueueEntry(int candidateId, long gain, double density) {
        private static QueueEntry of(int candidateId, long gain, int size) {
            return new QueueEntry(candidateId, gain, (double) gain / size);
        }
    }
}
