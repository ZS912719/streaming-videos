package com.hashcode.streaming.solution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Mutable cache placement produced by a solver. */
public final class Solution {
    private final List<Set<Integer>> videosByCache;
    public Solution(int cacheCount) {
        videosByCache = new ArrayList<>(cacheCount);
        for (int i = 0; i < cacheCount; i++) videosByCache.add(new LinkedHashSet<>());
    }
    public boolean addVideo(int cacheId, int videoId) { return videosByCache.get(cacheId).add(videoId); }
    public boolean contains(int cacheId, int videoId) { return videosByCache.get(cacheId).contains(videoId); }
    public Set<Integer> videosInCache(int cacheId) {
        return Collections.unmodifiableSet(videosByCache.get(cacheId));
    }
    public int nonEmptyCacheCount() {
        int count = 0;
        for (Set<Integer> videos : videosByCache) if (!videos.isEmpty()) count++;
        return count;
    }
}
