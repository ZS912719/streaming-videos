package com.hashcode.streaming.solver;

import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fills every cache from one global video-popularity-per-megabyte ranking. */
public final class BaselineSolver {
    public Solution solve(ProblemInstance problem) {
        long[] popularity = new long[problem.videoCount()];
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                popularity[request.videoId()] += request.requestCount();
            }
        }

        List<Integer> ranking = new ArrayList<>(problem.videoCount());
        for (int videoId = 0; videoId < problem.videoCount(); videoId++) {
            if (popularity[videoId] > 0) ranking.add(videoId);
        }
        ranking.sort(Comparator
                .<Integer>comparingDouble(videoId ->
                        (double) popularity[videoId] / problem.videoSizes()[videoId])
                .reversed()
                .thenComparing(Comparator.<Integer>comparingLong(
                        videoId -> popularity[videoId]).reversed())
                .thenComparingInt(Integer::intValue));

        Solution solution = new Solution(problem.cacheCount());
        for (int cacheId = 0; cacheId < problem.cacheCount(); cacheId++) {
            int remainingCapacity = problem.cacheCapacity();
            for (int videoId : ranking) {
                int size = problem.videoSizes()[videoId];
                if (size <= remainingCapacity) {
                    solution.addVideo(cacheId, videoId);
                    remainingCapacity -= size;
                }
            }
        }
        return solution;
    }
}
