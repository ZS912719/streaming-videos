package com.hashcode.streaming.scoring;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;

/** Rejects invalid video IDs and over-capacity cache placements. */
public final class SolutionValidator {
    public void validate(ProblemInstance problem, Solution solution) {
        for (int cacheId = 0; cacheId < problem.cacheCount(); cacheId++) {
            long usedCapacity = 0L;
            for (int videoId : solution.videosInCache(cacheId)) {
                if (videoId < 0 || videoId >= problem.videoCount()) {
                    throw new IllegalArgumentException("Invalid video ID " + videoId);
                }
                usedCapacity += problem.videoSizes()[videoId];
            }
            if (usedCapacity > problem.cacheCapacity()) {
                throw new IllegalArgumentException(
                        "Cache " + cacheId + " exceeds capacity: " + usedCapacity);
            }
        }
    }
}
