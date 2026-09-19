package com.hashcode.streaming.io;

import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses the official ASCII input format without allocating one String per token. */
public final class InputParser {
    public ProblemInstance parse(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            FastIntReader reader = new FastIntReader(input);
            int videoCount = reader.nextInt();
            int endpointCount = reader.nextInt();
            int requestDescriptionCount = reader.nextInt();
            int cacheCount = reader.nextInt();
            int cacheCapacity = reader.nextInt();
            int[] videoSizes = new int[videoCount];
            for (int i = 0; i < videoCount; i++) videoSizes[i] = reader.nextInt();

            List<Endpoint> endpoints = new ArrayList<>(endpointCount);
            for (int endpointId = 0; endpointId < endpointCount; endpointId++) {
                int dataCenterLatency = reader.nextInt();
                int connectionCount = reader.nextInt();
                Endpoint endpoint = new Endpoint(dataCenterLatency, connectionCount);
                for (int i = 0; i < connectionCount; i++) {
                    endpoint.addConnection(new CacheConnection(reader.nextInt(), reader.nextInt()));
                }
                endpoints.add(endpoint);
            }

            long totalRequests = 0L;
            for (int i = 0; i < requestDescriptionCount; i++) {
                int videoId = reader.nextInt();
                int endpointId = reader.nextInt();
                int count = reader.nextInt();
                endpoints.get(endpointId).addRequest(videoId, count);
                totalRequests += count;
            }
            return new ProblemInstance(videoCount, endpointCount, cacheCount, cacheCapacity,
                    videoSizes, List.copyOf(endpoints), totalRequests);
        }
    }

    private static final class FastIntReader {
        private final InputStream input;
        private final byte[] buffer = new byte[1 << 16];
        private int position;
        private int limit;
        private FastIntReader(InputStream input) { this.input = input; }

        private int nextInt() throws IOException {
            int current;
            do { current = read(); } while (current <= ' ' && current != -1);
            if (current == -1) throw new EOFException("Unexpected end of input");
            int value = 0;
            while (current > ' ') {
                value = value * 10 + current - '0';
                current = read();
            }
            return value;
        }

        private int read() throws IOException {
            if (position >= limit) {
                limit = input.read(buffer);
                position = 0;
                if (limit < 0) return -1;
            }
            return buffer[position++];
        }
    }
}
