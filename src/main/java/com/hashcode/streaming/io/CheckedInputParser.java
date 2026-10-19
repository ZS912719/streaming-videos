package com.hashcode.streaming.io;

import com.hashcode.streaming.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Checked extended counts, with a proven long-objective bound; legacy parser is unchanged. */
public final class CheckedInputParser {
    public ProblemInstance parse(Path path) throws IOException {
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(path), 1 << 16)) {
            Reader r = new Reader(stream);
            int v = r.integer(1, 10000), e = r.integer(1, 1000), descriptions = r.integer(1, 1000000);
            int c = r.integer(1, 1000), capacity = r.integer(1, 500000);
            int[] sizes = new int[v];
            for (int i = 0; i < v; i++) sizes[i] = r.integer(1, 1000);
            List<Endpoint> endpoints = new ArrayList<>();
            for (int i = 0; i < e; i++) {
                int dc = r.integer(2, 4000), degree = r.integer(0, c);
                Endpoint endpoint = new Endpoint(dc, degree);
                boolean[] seen = new boolean[c];
                for (int j = 0; j < degree; j++) {
                    int cache = r.integer(0, c-1), latency = r.integer(1, Math.min(500, dc-1));
                    if (seen[cache]) throw new IOException("Duplicate cache connection");
                    seen[cache] = true;
                    endpoint.addConnection(new CacheConnection(cache, latency));
                }
                endpoints.add(endpoint);
            }
            long total = 0, upper = 0;
            for (int i = 0; i < descriptions; i++) {
                int video = r.integer(0, v-1), endpoint = r.integer(0, e-1);
                long count = r.number();
                if (count <= 0) throw new IOException("Request counts must be positive");
                total = Math.addExact(total, count);
                upper = Math.addExact(upper, Math.multiplyExact(count, endpoints.get(endpoint).dataCenterLatency()));
                // Transfer sums and differences have ample signed-long headroom.
                if (upper > Long.MAX_VALUE/4) throw new IOException("Objective exceeds supported exact-long range");
                endpoints.get(endpoint).addRequest(video, count);
            }
            if (r.nextNonspace() != -1) throw new IOException("Trailing input data");
            return new ProblemInstance(v, e, c, capacity, sizes, List.copyOf(endpoints), total);
        } catch (ArithmeticException error) {
            throw new IOException("Input arithmetic exceeds supported exact-long range", error);
        }
    }

    private static final class Reader {
        final InputStream input;
        Reader(InputStream input) { this.input = input; }
        int nextNonspace() throws IOException {
            int ch;
            do { ch = input.read(); } while (ch >= 0 && ch <= 32);
            return ch;
        }
        long number() throws IOException {
            int ch = nextNonspace();
            if (ch < '0' || ch > '9') throw new IOException("Expected a nonnegative integer");
            long value = 0;
            do {
                if (ch < '0' || ch > '9') throw new IOException("Invalid integer token");
                value = Math.addExact(Math.multiplyExact(value, 10), ch-'0');
                ch = input.read();
            } while (ch > 32);
            return value;
        }
        int integer(int min, int max) throws IOException {
            long value = number();
            if (value < min || value > max) throw new IOException("Integer outside ["+min+","+max+"]: "+value);
            return (int) value;
        }
    }
}
