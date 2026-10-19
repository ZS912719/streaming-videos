package com.hashcode.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashcode.streaming.experiments.ScientificExperimentRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScientificWorkspaceTest {
    @TempDir Path directory;

    @Test void runsAllSixStrategiesThroughTheScientificWorkerContract() throws Exception {
        Path input = directory.resolve("input.in");
        Files.copy(Path.of("../examples/opportunity-cost.in"), input);
        SessionController.validateInput(input);
        Map<String, Long> expected = Map.of("baseline", 499500L, "greedy", 549500L,
                "ocag", 925000L, "local-search", 549500L, "ocag-ls", 925000L,
                "local-search-two", 549500L);
        assertEquals(expected.keySet(), java.util.Set.copyOf(SessionController.ALGORITHMS));
        for (String algorithm : SessionController.ALGORITHMS) {
            Path metrics = directory.resolve(algorithm + ".metrics.csv");
            Path output = directory.resolve(algorithm + ".out");
            Path localTrace = directory.resolve(algorithm + ".ls-trace.csv");
            Path ocagTrace = directory.resolve(algorithm + ".ocag-trace.csv");
            ScientificExperimentRunner.main(new String[]{SessionController.workerAlgorithm(algorithm),
                    input.toString(), output.toString(), metrics.toString(),
                    localTrace.toString(), ocagTrace.toString()});
            var row = SessionController.readMetrics(metrics);
            assertEquals(expected.get(algorithm).longValue(), Long.parseLong(row.get("internal_final_score")));
            assertEquals(SessionController.workerAlgorithm(algorithm), row.get("algorithm"));
            assertTrue(Files.size(output) > 0);
            assertEquals(algorithm.startsWith("local-search") || algorithm.equals("ocag-ls"), Files.exists(localTrace));
            assertEquals(algorithm.startsWith("ocag"), Files.exists(ocagTrace));
        }
    }

    @Test void optimizedStrategyEscapesTheSingleRemovalTrapAndExportsBothRemovals() throws Exception {
        Path input = directory.resolve("input.in");
        Files.copy(Path.of("../examples/two-removal.in"), input);
        for (String algorithm : java.util.List.of("local-search", "local-search-two")) {
            Path metrics = directory.resolve(algorithm + ".metrics.csv");
            Path trace = directory.resolve(algorithm + ".ls-trace.csv");
            ScientificExperimentRunner.main(new String[]{SessionController.workerAlgorithm(algorithm),
                    input.toString(), directory.resolve(algorithm + ".out").toString(),
                    metrics.toString(), trace.toString()});
            var row = SessionController.readMetrics(metrics);
            boolean optimized = algorithm.equals("local-search-two");
            assertEquals(optimized ? "471428" : "428571", row.get("internal_final_score"));
            assertEquals(optimized ? "two_removals" : "complete", row.get("ls_neighborhood"));
            assertEquals(optimized ? "1" : "0", row.get("ls_double_replacements"));
            assertEquals("no_improving_move", row.get("ls_termination"));
            if (optimized) {
                assertTrue(Double.parseDouble(row.get("ls_last_improvement_ms")) >= 0);
                var lines = Files.readAllLines(trace);
                String[] headers = lines.get(0).split(","), move = lines.get(2).split(",");
                int first = java.util.Arrays.asList(headers).indexOf("removed_video_id");
                int second = java.util.Arrays.asList(headers).indexOf("second_removed_video_id");
                assertTrue(Integer.parseInt(move[first]) >= 0);
                assertTrue(Integer.parseInt(move[second]) >= 0);
                assertNotEquals(move[first], move[second]);
            } else {
                assertEquals("", row.get("ls_last_improvement_ms"));
            }
        }
    }

    @Test void onlyAllowsNamedSessionArtifacts() {
        assertTrue(SessionController.allowedFile("input.in"));
        for (String algorithm : SessionController.ALGORITHMS) {
            for (String suffix : java.util.List.of("out", "log", "metrics.csv", "ls-trace.csv", "ocag-trace.csv")) {
                assertTrue(SessionController.allowedFile(algorithm + "." + suffix));
            }
        }
        assertFalse(SessionController.allowedFile("../session.json"));
        assertFalse(SessionController.allowedFile("session.json"));
        assertFalse(SessionController.allowedFile("unknown.log"));
    }

    @Test void readsOldSessionsWithoutInventingMetrics() throws Exception {
        var session = new ObjectMapper().readValue("""
                {"id":"old","status":"complete","results":[
                  {"algorithm":"local-search","status":"complete","score":529411,
                   "diagnostics":"Legacy diagnostics"}]}
                """, SessionController.Session.class);
        assertEquals(1, session.results.size());
        assertTrue(session.results.get(0).metrics.isEmpty());
        assertFalse(session.results.get(0).logAvailable);
    }

    @Test void keepsPersistedFiveStrategySessionsUnchanged() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Path saved = directory.resolve(id);
        Files.createDirectory(saved);
        var session = new SessionController.Session();
        session.id = id;
        session.createdAt = "2026-10-18T12:00:00Z";
        session.status = "complete";
        for (String algorithm : java.util.List.of("baseline", "greedy", "ocag", "local-search", "ocag-ls")) {
            var result = new SessionController.Result();
            result.algorithm = algorithm;
            result.status = "complete";
            session.results.add(result);
        }
        new ObjectMapper().writeValue(saved.resolve("session.json").toFile(), session);
        var controller = new SessionController(new ObjectMapper(), directory.toString(), 5);
        try {
            assertEquals(5, controller.get(id).results.size());
            assertFalse(controller.get(id).results.stream().anyMatch(r -> r.algorithm.equals("local-search-two")));
        } finally {
            controller.close();
        }
    }

    @Test void rejectsMalformedMetricsAndNonImprovingCacheLatency() throws IOException {
        Path metrics = directory.resolve("bad.csv");
        Files.writeString(metrics, "algorithm,internal_final_score\ngreedy\n");
        assertThrows(IOException.class, () -> SessionController.readMetrics(metrics));
        Path input = directory.resolve("bad.in");
        Files.writeString(input, "1 1 1 1 10\n6\n1000 1\n0 1000\n0 0 7\n");
        assertThrows(IOException.class, () -> SessionController.validateInput(input));
    }

    @Test void concurrentHistoryReadsDoNotBlockAtomicStateReplacement() throws Exception {
        var controller = new SessionController(new ObjectMapper(), directory.toString(), 5);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var session = new SessionController.Session();
            session.id = java.util.UUID.randomUUID().toString();
            session.status = "complete";
            Files.createDirectory(directory.resolve(session.id));
            controller.save(session);
            var writer = workers.submit(() -> {
                for (int i = 0; i < 100; i++) controller.save(session);
                return null;
            });
            var reader = workers.submit(() -> {
                for (int i = 0; i < 100; i++) assertEquals("complete", controller.get(session.id).status);
                return null;
            });
            writer.get(15, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            controller.close();
        }
    }
}
