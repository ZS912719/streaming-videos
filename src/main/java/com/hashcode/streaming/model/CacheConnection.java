package com.hashcode.streaming.model;

/** An endpoint-to-cache connection and its latency in milliseconds. */
public record CacheConnection(int cacheId, int latency) {
}
