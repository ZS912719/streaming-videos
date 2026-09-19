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

/** Dynamic marginal-gain-per-megabyte greedy solver. */
public final class GreedySolver {
    public Solution solve(ProblemInstance problem) {
        List<List<EndpointLink>> endpointsByCache = reverseConnections(problem);
        Map<Long, Long> initialGains = calculateInitialGains(problem);
        PriorityQueue<Candidate> queue = new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::density).reversed()
                        .thenComparing(Comparator.comparingLong(Candidate::gain).reversed()));

        for (Map.Entry<Long, Long> entry : initialGains.entrySet()) {
            int cacheId = unpackHigh(entry.getKey());
            int videoId = unpackLow(entry.getKey());
            if (problem.videoSizes()[videoId] <= problem.cacheCapacity() && entry.getValue() > 0) {
                queue.add(Candidate.of(cacheId, videoId, entry.getValue(), problem.videoSizes()[videoId]));
            }
        }

        Solution solution = new Solution(problem.cacheCount());
        int[] remainingCapacity = new int[problem.cacheCount()];
        java.util.Arrays.fill(remainingCapacity, problem.cacheCapacity());
        Map<Long, Integer> bestLatencies = initializeBestLatencies(problem);

        while (!queue.isEmpty()) {
            Candidate candidate = queue.poll();
            int size = problem.videoSizes()[candidate.videoId()];
            if (size > remainingCapacity[candidate.cacheId()]
                    || solution.contains(candidate.cacheId(), candidate.videoId())) continue;

            long currentGain = marginalGain(candidate.cacheId(), candidate.videoId(),
                    endpointsByCache, bestLatencies);
            if (currentGain <= 0) continue;
            Candidate refreshed = Candidate.of(candidate.cacheId(), candidate.videoId(), currentGain, size);

            // Queue values are upper bounds: placements only decrease future marginal gains.
            if (!queue.isEmpty() && refreshed.density() + 1e-12 < queue.peek().density()) {
                queue.add(refreshed);
                continue;
            }

            solution.addVideo(refreshed.cacheId(), refreshed.videoId());
            remainingCapacity[refreshed.cacheId()] -= size;
            applyPlacement(refreshed.cacheId(), refreshed.videoId(), endpointsByCache, bestLatencies);
        }
        return solution;
    }

    private static List<List<EndpointLink>> reverseConnections(ProblemInstance problem) {
        List<List<EndpointLink>> result = new ArrayList<>(problem.cacheCount());
        for (int i = 0; i < problem.cacheCount(); i++) result.add(new ArrayList<>());
        for (int endpointId = 0; endpointId < problem.endpointCount(); endpointId++) {
            Endpoint endpoint = problem.endpoints().get(endpointId);
            for (CacheConnection connection : endpoint.connections()) {
                result.get(connection.cacheId()).add(
                        new EndpointLink(endpointId, connection.latency(), endpoint));
            }
        }
        return result;
    }

    private static Map<Long, Long> calculateInitialGains(ProblemInstance problem) {
        Map<Long, Long> gains = new HashMap<>();
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                for (CacheConnection connection : endpoint.connections()) {
                    long gain = request.requestCount()
                            * (long) (endpoint.dataCenterLatency() - connection.latency());
                    gains.merge(pack(connection.cacheId(), request.videoId()), gain, Long::sum);
                }
            }
        }
        return gains;
    }

    private static Map<Long, Integer> initializeBestLatencies(ProblemInstance problem) {
        Map<Long, Integer> result = new HashMap<>();
        for (int endpointId = 0; endpointId < problem.endpointCount(); endpointId++) {
            Endpoint endpoint = problem.endpoints().get(endpointId);
            for (int videoId : endpoint.requestsByVideo().keySet()) {
                result.put(pack(endpointId, videoId), endpoint.dataCenterLatency());
            }
        }
        return result;
    }

    private static long marginalGain(int cacheId, int videoId,
            List<List<EndpointLink>> endpointsByCache, Map<Long, Integer> bestLatencies) {
        long gain = 0L;
        for (EndpointLink link : endpointsByCache.get(cacheId)) {
            RequestDemand request = link.endpoint().requestsByVideo().get(videoId);
            if (request == null) continue;
            int currentBest = bestLatencies.get(pack(link.endpointId(), videoId));
            if (link.cacheLatency() < currentBest) {
                gain += request.requestCount() * (long) (currentBest - link.cacheLatency());
            }
        }
        return gain;
    }

    private static void applyPlacement(int cacheId, int videoId,
            List<List<EndpointLink>> endpointsByCache, Map<Long, Integer> bestLatencies) {
        for (EndpointLink link : endpointsByCache.get(cacheId)) {
            if (!link.endpoint().requestsByVideo().containsKey(videoId)) continue;
            long key = pack(link.endpointId(), videoId);
            int currentBest = bestLatencies.get(key);
            if (link.cacheLatency() < currentBest) bestLatencies.put(key, link.cacheLatency());
        }
    }

    private static long pack(int high, int low) {
        return ((long) high << 32) | (low & 0xffffffffL);
    }
    private static int unpackHigh(long key) { return (int) (key >>> 32); }
    private static int unpackLow(long key) { return (int) key; }

    private record EndpointLink(int endpointId, int cacheLatency, Endpoint endpoint) { }
    private record Candidate(int cacheId, int videoId, long gain, double density) {
        private static Candidate of(int cacheId, int videoId, long gain, int size) {
            return new Candidate(cacheId, videoId, gain, (double) gain / size);
        }
    }
}
