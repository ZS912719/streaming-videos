package com.hashcode.streaming.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Endpoint data, including cache links and sparse video requests. */
public final class Endpoint {
    private final int dataCenterLatency;
    private final List<CacheConnection> connections;
    private final Map<Integer, RequestDemand> requestsByVideo = new HashMap<>();

    public Endpoint(int dataCenterLatency, int connectionCount) {
        this.dataCenterLatency = dataCenterLatency;
        this.connections = new ArrayList<>(connectionCount);
    }

    public int dataCenterLatency() { return dataCenterLatency; }
    public List<CacheConnection> connections() { return connections; }
    public Map<Integer, RequestDemand> requestsByVideo() { return requestsByVideo; }
    public void addConnection(CacheConnection connection) { connections.add(connection); }

    public void addRequest(int videoId, long count) {
        requestsByVideo.compute(videoId, (ignored, current) -> {
            if (current == null) return new RequestDemand(videoId, count);
            current.addRequests(count);
            return current;
        });
    }
}
