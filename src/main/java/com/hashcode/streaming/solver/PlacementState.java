package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.*;
import com.hashcode.streaming.solution.Solution;
import java.util.*;

/** Request index and exact serving latencies used only by the advanced experiments. */
final class PlacementState {
    final ProblemInstance problem;
    final Solution solution;
    final int caches, videos;
    final boolean[] contains;
    final int[] used;
    final List<Demand>[] demands;
    final List<Demand>[] endpointDemands;
    final List<Integer>[] cacheEndpoints;
    final int[][] latency;

    @SuppressWarnings("unchecked")
    PlacementState(ProblemInstance problem, Solution solution) {
        this.problem = problem;
        this.solution = solution;
        caches = problem.cacheCount();
        videos = problem.videoCount();
        contains = new boolean[Math.multiplyExact(caches, videos)];
        used = new int[caches];
        for (int c = 0; c < caches; c++) for (int v : solution.videosInCache(c)) {
            contains[c * videos + v] = true;
            used[c] += problem.videoSizes()[v];
        }
        demands = (List<Demand>[]) new List<?>[videos];
        for (int v = 0; v < videos; v++) demands[v] = new ArrayList<>();
        endpointDemands = (List<Demand>[]) new List<?>[problem.endpointCount()];
        cacheEndpoints = (List<Integer>[]) new List<?>[caches];
        for (int c = 0; c < caches; c++) cacheEndpoints[c] = new ArrayList<>();
        latency = new int[problem.endpointCount()][caches];
        for (int e = 0; e < problem.endpointCount(); e++) {
            endpointDemands[e] = new ArrayList<>();
            Endpoint endpoint = problem.endpoints().get(e);
            Arrays.fill(latency[e], endpoint.dataCenterLatency());
            for (CacheConnection link : endpoint.connections()) {
                latency[e][link.cacheId()] = Math.min(endpoint.dataCenterLatency(), link.latency());
                cacheEndpoints[link.cacheId()].add(e);
            }
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                Demand demand = new Demand(e, request.videoId(), request.requestCount());
                demands[request.videoId()].add(demand);
                endpointDemands[e].add(demand);
            }
        }
        for (int v = 0; v < videos; v++) refresh(v);
    }

    boolean has(int c, int v) { return contains[c * videos + v]; }

    void add(int c, int v) {
        if (has(c, v)) throw new IllegalStateException("Duplicate placement");
        contains[c * videos + v] = true;
        used[c] += problem.videoSizes()[v];
        solution.addVideo(c, v);
    }

    void remove(int c, int v) {
        if (!has(c, v)) throw new IllegalStateException("Missing placement");
        contains[c * videos + v] = false;
        used[c] -= problem.videoSizes()[v];
        solution.removeVideo(c, v);
    }

    void refresh(int v) {
        for (Demand demand : demands[v]) {
            Endpoint endpoint = problem.endpoints().get(demand.endpoint);
            demand.best = endpoint.dataCenterLatency();
            demand.second = demand.best;
            demand.bestCache = -1;
            for (CacheConnection link : endpoint.connections()) if (has(link.cacheId(), v)) {
                int value = link.latency();
                if (value < demand.best) {
                    demand.second = demand.best;
                    demand.best = value;
                    demand.bestCache = link.cacheId();
                } else if (value < demand.second) demand.second = value;
            }
        }
    }

    void gains(int v, long[] destination, int offset) {
        Arrays.fill(destination, offset, offset + caches, 0L);
        for (Demand demand : demands[v]) {
            for (CacheConnection link : problem.endpoints().get(demand.endpoint).connections()) {
                destination[offset + link.cacheId()] += demand.count
                        * (long) Math.max(0, demand.best - link.latency());
            }
        }
    }

    /** Exact gain of moving a stored video from source to every destination. */
    void transfers(int v, int source, long[] destination, int offset) {
        long loss = 0L;
        for (Demand demand : demands[v]) {
            int without = demand.bestCache == source ? demand.second : demand.best;
            loss += demand.count * (long) (without - demand.best);
        }
        Arrays.fill(destination, offset, offset + caches, -loss);
        for (Demand demand : demands[v]) {
            int without = demand.bestCache == source ? demand.second : demand.best;
            for (CacheConnection link : problem.endpoints().get(demand.endpoint).connections()) {
                destination[offset + link.cacheId()] += demand.count
                        * (long) Math.max(0, without - link.latency());
            }
        }
    }

    long benefit(int c, int v) {
        long result = 0L;
        for (Demand demand : demands[v]) {
            int without = demand.bestCache == c ? demand.second : demand.best;
            result += demand.count * (long) Math.max(0, without - latency[demand.endpoint][c]);
        }
        return result;
    }

    void benefits(int c, long[] destination) {
        Arrays.fill(destination, 0L);
        for (int e : cacheEndpoints[c]) for (Demand demand : endpointDemands[e]) {
            int without = demand.bestCache == c ? demand.second : demand.best;
            destination[demand.video] += demand.count * (long) Math.max(0, without - latency[e][c]);
        }
    }

    long raw() {
        long total = 0L;
        for (List<Demand> list : demands) for (Demand demand : list) {
            total += demand.count * (long) (problem.endpoints().get(demand.endpoint)
                    .dataCenterLatency() - demand.best);
        }
        return total;
    }

    static long fullRaw(ProblemInstance problem, Solution solution) {
        long total = 0L;
        for (Endpoint endpoint : problem.endpoints()) for (RequestDemand demand
                : endpoint.requestsByVideo().values()) {
            int best = endpoint.dataCenterLatency();
            for (CacheConnection link : endpoint.connections()) {
                if (solution.contains(link.cacheId(), demand.videoId())) best = Math.min(best, link.latency());
            }
            total += demand.requestCount() * (long) (endpoint.dataCenterLatency() - best);
        }
        return total;
    }

    static final class Demand {
        final int endpoint;
        final int video;
        final long count;
        int best, second, bestCache;
        Demand(int endpoint, int video, long count) { this.endpoint = endpoint; this.video = video; this.count = count; }
    }
}
