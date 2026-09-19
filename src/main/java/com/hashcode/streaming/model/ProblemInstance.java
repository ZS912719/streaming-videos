package com.hashcode.streaming.model;

import java.util.List;

/** Immutable top-level problem data. */
public record ProblemInstance(int videoCount, int endpointCount, int cacheCount,
        int cacheCapacity, int[] videoSizes, List<Endpoint> endpoints,
        long totalRequestCount) {
}
