package com.hashcode.streaming.scoring;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;

/** Reproduces the official floor(1000 * saved milliseconds / requests) score. */
public final class ScoreCalculator {
    public long calculate(ProblemInstance problem, Solution solution) {
        if (problem.totalRequestCount() == 0) return 0L;
        long totalSavedMilliseconds = 0L;
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                int bestLatency = endpoint.dataCenterLatency();
                for (CacheConnection connection : endpoint.connections()) {
                    if (solution.contains(connection.cacheId(), request.videoId())) {
                        bestLatency = Math.min(bestLatency, connection.latency());
                    }
                }
                totalSavedMilliseconds += request.requestCount()
                        * (long) (endpoint.dataCenterLatency() - bestLatency);
            }
        }
        return totalSavedMilliseconds * 1000L / problem.totalRequestCount();
    }
}
