package com.hashcode.streaming.io;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.solution.Solution;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes non-empty caches in the official submission format. */
public final class SolutionWriter {
    public void write(Path path, ProblemInstance problem, Solution solution) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII)) {
            writer.write(Integer.toString(solution.nonEmptyCacheCount()));
            writer.newLine();
            for (int cacheId = 0; cacheId < problem.cacheCount(); cacheId++) {
                if (solution.videosInCache(cacheId).isEmpty()) continue;
                writer.write(Integer.toString(cacheId));
                for (int videoId : solution.videosInCache(cacheId)) {
                    writer.write(' ');
                    writer.write(Integer.toString(videoId));
                }
                writer.newLine();
            }
        }
    }
}
