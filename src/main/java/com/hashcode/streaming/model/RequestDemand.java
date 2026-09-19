package com.hashcode.streaming.model;

/** Aggregated requests for one video from one endpoint. */
public final class RequestDemand {
    private final int videoId;
    private long requestCount;

    public RequestDemand(int videoId, long requestCount) {
        this.videoId = videoId;
        this.requestCount = requestCount;
    }

    public int videoId() { return videoId; }
    public long requestCount() { return requestCount; }
    public void addRequests(long additionalRequests) { requestCount += additionalRequests; }
}
