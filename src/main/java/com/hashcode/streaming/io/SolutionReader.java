package com.hashcode.streaming.io;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strictly reads an official-format placement for experiment reuse. */
public final class SolutionReader {
    public Solution read(Path path, ProblemInstance problem) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) throw new IOException("Empty solution file");
        int declaredCaches = parse(lines.get(0), "cache count");
        if (declaredCaches < 0 || declaredCaches > problem.cacheCount()) {
            throw new IOException("Invalid cache count: " + declaredCaches);
        }
        if (lines.size() != declaredCaches + 1) {
            throw new IOException("Declared " + declaredCaches + " cache lines, found "
                    + (lines.size() - 1));
        }

        Solution solution = new Solution(problem.cacheCount());
        Set<Integer> seenCaches = new HashSet<>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex).trim();
            if (line.isEmpty()) throw new IOException("Empty cache line " + lineIndex);
            String[] tokens = line.split("\\s+");
            int cacheId = parse(tokens[0], "cache ID");
            if (cacheId < 0 || cacheId >= problem.cacheCount()) {
                throw new IOException("Invalid cache ID: " + cacheId);
            }
            if (!seenCaches.add(cacheId)) throw new IOException("Duplicate cache: " + cacheId);
            for (int token = 1; token < tokens.length; token++) {
                int videoId = parse(tokens[token], "video ID");
                if (videoId < 0 || videoId >= problem.videoCount()) {
                    throw new IOException("Invalid video ID: " + videoId);
                }
                if (!solution.addVideo(cacheId, videoId)) {
                    throw new IOException("Duplicate video " + videoId + " in cache " + cacheId);
                }
            }
        }
        new SolutionValidator().validate(problem, solution);
        return solution;
    }

    private static int parse(String token, String field) throws IOException {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException error) {
            throw new IOException("Invalid " + field + ": " + token, error);
        }
    }
}
