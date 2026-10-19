package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionReader;
import com.hashcode.streaming.io.SolutionWriter;
import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.scoring.SolutionValidator;
import com.hashcode.streaming.solution.Solution;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Bounded deterministic diagnostic of exact two-cache video exchanges. */
public final class CrossCacheSwapProbe {
    private CrossCacheSwapProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 6) {
            throw new IllegalArgumentException("Usage: CrossCacheSwapProbe <input> <solution>"
                    + " <cache-pair-limit> <videos-per-cache> [max-rounds result.out]");
        }
        ProblemInstance problem = new InputParser().parse(Path.of(args[0]));
        Solution solution = new SolutionReader().read(Path.of(args[1]), problem);
        int pairLimit = Integer.parseInt(args[2]);
        int videoLimit = Integer.parseInt(args[3]);
        int maxRounds = args.length == 6 ? Integer.parseInt(args[4]) : 1;
        if (pairLimit < 1 || videoLimit < 1 || maxRounds < 1) {
            throw new IllegalArgumentException("Positive limits required");
        }
        List<List<Demand>> byVideo = new ArrayList<>(problem.videoCount());
        long[] popularity = new long[problem.videoCount()];
        for (int video = 0; video < problem.videoCount(); video++) byVideo.add(new ArrayList<>());
        for (Endpoint endpoint : problem.endpoints()) {
            for (RequestDemand demand : endpoint.requestsByVideo().values()) {
                byVideo.get(demand.videoId()).add(new Demand(endpoint, demand.requestCount()));
                popularity[demand.videoId()] += demand.requestCount();
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (int first = 0; first < problem.cacheCount(); first++) {
            for (int second = first + 1; second < problem.cacheCount(); second++) {
                pairs.add(new Pair(first, second));
            }
        }
        Collections.shuffle(pairs, new Random(20_170_317L));
        long startNanos = System.nanoTime();
        long evaluated = 0L;
        long totalGain = 0L;
        int accepted = 0;
        int sampled = Math.min(pairLimit, pairs.size());
        long originalScore = new ScoreCalculator().calculate(problem, solution);
        long currentRaw = savedAll(byVideo, solution);
        for (int round = 1; round <= maxRounds; round++) {
            List<List<Integer>> selected = new ArrayList<>(problem.cacheCount());
            int[] used = new int[problem.cacheCount()];
            for (int cache = 0; cache < problem.cacheCount(); cache++) {
                List<Integer> videos = new ArrayList<>(solution.videosInCache(cache));
                for (int video : videos) used[cache] += problem.videoSizes()[video];
                videos.sort(Comparator.<Integer>comparingLong(v -> popularity[v]).reversed()
                        .thenComparingInt(Integer::intValue));
                selected.add(videos.subList(0, Math.min(videoLimit, videos.size())));
            }
            long bestDelta = 0L;
            Pair bestPair = null;
            int bestFirstVideo = -1;
            int bestSecondVideo = -1;
            for (int pairIndex = 0; pairIndex < sampled; pairIndex++) {
                Pair pair = pairs.get(pairIndex);
                int first = pair.first();
                int second = pair.second();
                for (int firstVideo : selected.get(first)) {
                    for (int secondVideo : selected.get(second)) {
                        if (firstVideo == secondVideo
                                || solution.contains(first, secondVideo)
                                || solution.contains(second, firstVideo)
                                || used[first] - problem.videoSizes()[firstVideo]
                                    + problem.videoSizes()[secondVideo] > problem.cacheCapacity()
                                || used[second] - problem.videoSizes()[secondVideo]
                                    + problem.videoSizes()[firstVideo] > problem.cacheCapacity()) {
                            continue;
                        }
                        long before = saved(firstVideo, byVideo, solution)
                                + saved(secondVideo, byVideo, solution);
                        swap(solution, first, firstVideo, second, secondVideo);
                        long after = saved(firstVideo, byVideo, solution)
                                + saved(secondVideo, byVideo, solution);
                        swap(solution, first, secondVideo, second, firstVideo);
                        evaluated++;
                        long delta = after - before;
                        if (delta > bestDelta) {
                            bestDelta = delta;
                            bestPair = pair;
                            bestFirstVideo = firstVideo;
                            bestSecondVideo = secondVideo;
                        }
                    }
                }
            }
            if (bestPair == null) break;
            swap(solution, bestPair.first(), bestFirstVideo,
                    bestPair.second(), bestSecondVideo);
            new SolutionValidator().validate(problem, solution);
            long checkedRaw = savedAll(byVideo, solution);
            if (checkedRaw - currentRaw != bestDelta) {
                throw new IllegalStateException("Two-cache delta differs from full objective");
            }
            currentRaw = checkedRaw;
            totalGain += bestDelta;
            accepted++;
            System.out.printf("round=%d cache_a=%d video_a=%d cache_b=%d video_b=%d "
                            + "gain_request_ms=%d official_score=%d%n",
                    round, bestPair.first(), bestFirstVideo,
                    bestPair.second(), bestSecondVideo, bestDelta,
                    new ScoreCalculator().calculate(problem, solution));
        }
        long finalScore = new ScoreCalculator().calculate(problem, solution);
        if (args.length == 6) {
            new SolutionWriter().write(Path.of(args[5]), problem, solution);
        }
        System.out.printf("sampled_pairs=%d evaluated_feasible_exchanges=%d "
                        + "accepted_exchanges=%d gain_request_ms=%d "
                        + "original_score=%d final_score=%d search_ms=%.3f%n",
                sampled, evaluated, accepted, totalGain, originalScore, finalScore,
                (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private static long savedAll(List<List<Demand>> byVideo, Solution solution) {
        long total = 0L;
        for (int video = 0; video < byVideo.size(); video++) {
            total += saved(video, byVideo, solution);
        }
        return total;
    }

    private static long saved(int video, List<List<Demand>> byVideo, Solution solution) {
        long total = 0L;
        for (Demand demand : byVideo.get(video)) {
            int best = demand.endpoint().dataCenterLatency();
            for (CacheConnection connection : demand.endpoint().connections()) {
                if (solution.contains(connection.cacheId(), video)) {
                    best = Math.min(best, connection.latency());
                }
            }
            total += demand.count() * (long) (demand.endpoint().dataCenterLatency() - best);
        }
        return total;
    }

    private static void swap(Solution solution, int first, int firstVideo,
            int second, int secondVideo) {
        solution.removeVideo(first, firstVideo);
        solution.removeVideo(second, secondVideo);
        solution.addVideo(first, secondVideo);
        solution.addVideo(second, firstVideo);
    }

    private record Demand(Endpoint endpoint, long count) { }

    private record Pair(int first, int second) { }
}
