package com.hashcode.streaming.experiments;

import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.io.SolutionReader;
import com.hashcode.streaming.model.CacheConnection;
import com.hashcode.streaming.model.Endpoint;
import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.model.RequestDemand;
import com.hashcode.streaming.solution.Solution;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Paired fixed-placement evaluation under reproducible demand perturbations. */
public final class RobustnessExperiment {
    private static final int SCENARIOS = 30;
    private static final int FIRST_SEED = 10_001;
    private static final double[] NOISE_LEVELS = {0.05, 0.10, 0.20, 0.30};

    private RobustnessExperiment() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: RobustnessExperiment <input> <results.csv> <sample-dir>"
                    + " <algorithm=solution.out>...");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path results = Path.of(args[1]);
        Path sampleDirectory = Path.of(args[2]);
        ProblemInstance problem = new InputParser().parse(input);
        Map<String, Solution> solutions = new LinkedHashMap<>();
        SolutionReader reader = new SolutionReader();
        for (int argument = 3; argument < args.length; argument++) {
            int separator = args[argument].indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Expected algorithm=path");
            String algorithm = args[argument].substring(0, separator);
            Path path = Path.of(args[argument].substring(separator + 1));
            solutions.put(algorithm, reader.read(path, problem));
        }

        List<Demand> demands = enumerateDemands(problem, solutions);
        Files.createDirectories(results.toAbsolutePath().getParent());
        Files.createDirectories(sampleDirectory);
        try (BufferedWriter writer = Files.newBufferedWriter(results, StandardCharsets.UTF_8)) {
            writer.write("dataset,model,level,seed,scenario,algorithm,score,total_requests");
            writer.newLine();
            long[] nominal = demands.stream().mapToLong(Demand::requestCount).toArray();
            writeScenario(writer, input.getFileName().toString(), "nominal", "0", -1,
                    "nominal", solutions, demands, nominal);

            for (double level : NOISE_LEVELS) {
                for (int scenario = 0; scenario < SCENARIOS; scenario++) {
                    int seed = FIRST_SEED + scenario;
                    long[] counts = multiplicativeCounts(demands, level, seed);
                    String levelText = String.format(Locale.ROOT, "%.2f", level);
                    String id = "noise_" + levelText + "_seed_" + seed;
                    writeScenario(writer, input.getFileName().toString(), "multiplicative",
                            levelText, seed, id, solutions, demands, counts);
                    if (level == 0.20 && scenario == 0) {
                        writeInstance(sampleDirectory.resolve(id + ".in"), problem, demands, counts);
                    }
                }
            }

            for (int scenario = 0; scenario < SCENARIOS; scenario++) {
                int seed = FIRST_SEED + scenario;
                long[] counts = localizedShiftCounts(problem, demands, seed);
                String id = "localized_3x_seed_" + seed;
                writeScenario(writer, input.getFileName().toString(), "localized_shift",
                        "3x", seed, id, solutions, demands, counts);
                if (scenario == 0) {
                    writeInstance(sampleDirectory.resolve(id + ".in"), problem, demands, counts);
                }
            }
        }
    }

    private static List<Demand> enumerateDemands(
            ProblemInstance problem, Map<String, Solution> solutions) {
        List<Demand> demands = new ArrayList<>();
        for (int endpointId = 0; endpointId < problem.endpointCount(); endpointId++) {
            Endpoint endpoint = problem.endpoints().get(endpointId);
            for (RequestDemand request : endpoint.requestsByVideo().values()) {
                long[] savedLatencies = new long[solutions.size()];
                int algorithm = 0;
                for (Solution solution : solutions.values()) {
                    int best = endpoint.dataCenterLatency();
                    for (CacheConnection connection : endpoint.connections()) {
                        if (solution.contains(connection.cacheId(), request.videoId())) {
                            best = Math.min(best, connection.latency());
                        }
                    }
                    savedLatencies[algorithm++] = endpoint.dataCenterLatency() - best;
                }
                demands.add(new Demand(endpointId, request.videoId(), request.requestCount(),
                        savedLatencies));
            }
        }
        return demands;
    }

    private static long[] multiplicativeCounts(
            List<Demand> demands, double level, int seed) {
        Random random = new Random(seed * 31L + Double.doubleToLongBits(level));
        long[] counts = new long[demands.size()];
        for (int demand = 0; demand < demands.size(); demand++) {
            double multiplier = Math.max(0.0, 1.0 + level * random.nextGaussian());
            counts[demand] = Math.max(0L,
                    Math.round(demands.get(demand).requestCount() * multiplier));
        }
        return counts;
    }

    private static long[] localizedShiftCounts(
            ProblemInstance problem, List<Demand> demands, int seed) {
        Random random = new Random(seed * 97L + 17L);
        boolean[] selectedEndpoints = select(problem.endpointCount(), 0.20, random);
        boolean[] selectedVideos = select(problem.videoCount(), 0.10, random);
        long[] counts = new long[demands.size()];
        for (int demand = 0; demand < demands.size(); demand++) {
            Demand current = demands.get(demand);
            long multiplier = selectedEndpoints[current.endpointId()]
                    && selectedVideos[current.videoId()] ? 3L : 1L;
            counts[demand] = Math.min(Integer.MAX_VALUE,
                    current.requestCount() * multiplier);
        }
        return counts;
    }

    private static boolean[] select(int size, double fraction, Random random) {
        int[] values = new int[size];
        for (int index = 0; index < size; index++) values[index] = index;
        for (int index = size - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int temporary = values[index];
            values[index] = values[other];
            values[other] = temporary;
        }
        boolean[] selected = new boolean[size];
        int count = Math.max(1, (int) Math.round(size * fraction));
        for (int index = 0; index < count; index++) selected[values[index]] = true;
        return selected;
    }

    private static void writeScenario(BufferedWriter writer, String dataset,
            String model, String level, int seed, String scenario,
            Map<String, Solution> solutions, List<Demand> demands, long[] counts)
            throws Exception {
        long totalRequests = 0L;
        for (long count : counts) totalRequests += count;
        int algorithm = 0;
        for (String name : solutions.keySet()) {
            long saved = 0L;
            for (int demand = 0; demand < demands.size(); demand++) {
                saved += counts[demand] * demands.get(demand).savedLatencies()[algorithm];
            }
            long score = totalRequests == 0 ? 0 : saved * 1000L / totalRequests;
            writer.write(String.join(",", dataset, model, level, Integer.toString(seed),
                    scenario, name, Long.toString(score), Long.toString(totalRequests)));
            writer.newLine();
            algorithm++;
        }
    }

    private static void writeInstance(Path path, ProblemInstance problem,
            List<Demand> demands, long[] counts) throws Exception {
        int nonzero = 0;
        for (long count : counts) if (count > 0) nonzero++;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII)) {
            writer.write(problem.videoCount() + " " + problem.endpointCount() + " "
                    + nonzero + " " + problem.cacheCount() + " " + problem.cacheCapacity());
            writer.newLine();
            for (int video = 0; video < problem.videoCount(); video++) {
                if (video > 0) writer.write(' ');
                writer.write(Integer.toString(problem.videoSizes()[video]));
            }
            writer.newLine();
            for (Endpoint endpoint : problem.endpoints()) {
                writer.write(endpoint.dataCenterLatency() + " " + endpoint.connections().size());
                writer.newLine();
                for (CacheConnection connection : endpoint.connections()) {
                    writer.write(connection.cacheId() + " " + connection.latency());
                    writer.newLine();
                }
            }
            for (int demand = 0; demand < demands.size(); demand++) {
                if (counts[demand] <= 0) continue;
                Demand current = demands.get(demand);
                writer.write(current.videoId() + " " + current.endpointId() + " "
                        + counts[demand]);
                writer.newLine();
            }
        }
    }

    private record Demand(
            int endpointId, int videoId, long requestCount, long[] savedLatencies) { }
}
