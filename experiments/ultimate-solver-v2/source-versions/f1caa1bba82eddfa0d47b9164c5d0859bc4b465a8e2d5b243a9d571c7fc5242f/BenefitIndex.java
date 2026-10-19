package com.hashcode.streaming.solver;

import java.util.Arrays;

/** Lazy exact cache-excluded benefits. Only changed video columns need refreshing. */
final class BenefitIndex {
    private final PlacementState state;
    private final long[] values;
    private final boolean[] initialized;
    private final int[] rows;
    private int count;

    BenefitIndex(PlacementState state) {
        this.state=state;
        values=new long[Math.multiplyExact(state.caches,state.videos)];
        initialized=new boolean[state.caches];rows=new int[state.caches];
    }
    void row(int c,long[] destination) {
        int offset=c*state.videos;
        if(!initialized[c]) {
            state.benefits(c,destination);
            System.arraycopy(destination,0,values,offset,state.videos);
            initialized[c]=true;rows[count++]=c;
        }else System.arraycopy(values,offset,destination,0,state.videos);
    }
    /** Call after the video's serving latencies have been refreshed. */
    void refresh(int v) {
        for(int i=0;i<count;i++) {
            int c=rows[i];values[c*state.videos+v]=state.benefit(c,v);
        }
    }
    void verify() {
        long[] expected=new long[state.videos];
        for(int i=0;i<count;i++) {
            int c=rows[i];state.benefits(c,expected);
            if(!Arrays.equals(expected,0,state.videos,values,c*state.videos,(c+1)*state.videos))
                throw new AssertionError("Cached benefit row differs from full recomputation");
        }
    }
}
