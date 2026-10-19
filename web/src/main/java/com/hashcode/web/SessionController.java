package com.hashcode.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hashcode.streaming.io.InputParser;
import com.hashcode.streaming.model.ProblemInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Disk-backed sessions and bounded, isolated solver execution. */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final Path root;
    private final ObjectMapper mapper;
    private final int timeout;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private volatile Process activeProcess;
    static final List<String> ALGORITHMS = List.of("baseline", "greedy", "ocag", "local-search", "ocag-ls");

    public SessionController(ObjectMapper mapper, @Value("${streaming.sessions}") String directory,
            @Value("${streaming.timeout-seconds}") int timeout) throws IOException {
        this.mapper = mapper;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.timeout = timeout;
        Files.createDirectories(root);
        // An interrupted run is retained in history, with a truthful terminal state.
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path state = dir.resolve("session.json");
                if (!Files.exists(state)) continue;
                Session session = mapper.readValue(state.toFile(), Session.class);
                if (session.status.equals("queued") || session.status.equals("running")) {
                    session.status = "interrupted";
                    for (Result result : session.results) {
                        if (result.status.equals("running") || result.status.equals("pending")) {
                            result.status = "interrupted";
                            result.error = "Server restarted before this solver finished.";
                        }
                    }
                    save(session);
                }
            }
        }
    }

    @GetMapping
    public List<Session> history() throws IOException {
        List<Session> sessions = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (Files.exists(dir.resolve("session.json"))) sessions.add(read(dir.getFileName().toString()));
            }
        }
        sessions.sort(Comparator.comparing((Session s) -> s.createdAt).reversed());
        return sessions;
    }

    @GetMapping("/{id}")
    public Session get(@PathVariable String id) throws IOException { return read(id); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Session> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("input.in");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".in") || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a nonempty .in file.");
        }
        Session session = new Session();
        session.id = UUID.randomUUID().toString();
        session.filename = name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1);
        session.createdAt = Instant.now().toString();
        Path dir = directory(session.id);
        Files.createDirectory(dir);
        Path input = dir.resolve("input.in");
        file.transferTo(input);
        try {
            validateInput(input);
            ProblemInstance p = new InputParser().parse(input);
            long connections = p.endpoints().stream().mapToLong(e -> e.connections().size()).sum();
            long unique = p.endpoints().stream().mapToLong(e -> e.requestsByVideo().size()).sum();
            long edges = p.endpoints().stream().mapToLong(e -> (long)e.connections().size() * e.requestsByVideo().size()).sum();
            session.summary = new Summary(p.videoCount(), p.endpointCount(), p.cacheCount(),
                    p.cacheCapacity(), p.totalRequestCount(), unique, connections, edges, file.getSize());
        } catch (Exception error) {
            Files.deleteIfExists(input);
            Files.deleteIfExists(dir);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid input: " + error.getMessage());
        }
        for (String algorithm : ALGORITHMS) {
            Result result = new Result(); result.algorithm = algorithm; session.results.add(result);
        }
        save(session);
        try { executor.execute(() -> run(session)); }
        catch (RejectedExecutionException error) {
            session.status = "rejected"; save(session);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Queue is full. Try again later.");
        }
        // Read persisted state to avoid serializing a concurrently changing object.
        return ResponseEntity.accepted().body(read(session.id));
    }

    @GetMapping("/{id}/files/{file}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id, @PathVariable String file) {
        if (!allowedFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path path = directory(id).resolve(file);
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file + "\"").contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(path));
    }

    private void run(Session session) {
        session.status = "running";
        try {
            save(session);
            Path dir = directory(session.id);
            Path jar = Path.of("web/target/streaming-web.jar").toAbsolutePath();
            if (!Files.exists(jar)) jar = Path.of("target/streaming-web.jar").toAbsolutePath();
            for (Result result : session.results) {
                result.status = "running"; save(session);
                long start = System.nanoTime();
                try {
                    Path log = dir.resolve(result.algorithm + ".log");
                    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx2g", "-jar", jar.toString(), "--worker", workerAlgorithm(result.algorithm),
                            dir.resolve("input.in").toString(), dir.resolve(result.algorithm + ".out").toString(),
                            dir.resolve(result.algorithm + ".metrics.csv").toString(),
                            dir.resolve(result.algorithm + ".ls-trace.csv").toString(),
                            dir.resolve(result.algorithm + ".ocag-trace.csv").toString())
                            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    activeProcess = process;
                    boolean done = process.waitFor(timeout, TimeUnit.SECONDS);
                    if (!done) { process.destroyForcibly(); process.waitFor(); throw new IOException("Solver exceeded " + timeout + " seconds."); }
                    if (process.exitValue() != 0) throw new IOException("Solver failed (exit " + process.exitValue() + ").");
                    Map<String, String> metrics = readMetrics(dir.resolve(result.algorithm + ".metrics.csv"));
                    if (!workerAlgorithm(result.algorithm).equals(metrics.get("algorithm"))) {
                        throw new IOException("Solver metrics do not match the requested algorithm.");
                    }
                    long score = Long.parseLong(metrics.get("internal_final_score"));
                    List<String> lines = Files.readAllLines(dir.resolve(result.algorithm + ".out"));
                    result.nonEmptyCaches = Integer.parseInt(lines.get(0));
                    result.preview = String.join("\n", lines.stream().limit(9).map(l ->
                            l.length() > 600 ? l.substring(0,600) + " ..." : l).toList());
                    result.score = score;
                    result.metrics = metrics;
                    for (String suffix : List.of("metrics.csv", "ls-trace.csv", "ocag-trace.csv")) {
                        if (Files.exists(dir.resolve(result.algorithm + "." + suffix))) result.artifacts.add(suffix);
                    }
                    result.status = "complete";
                } catch (Exception error) {
                    result.status = "failed"; result.error = error.getMessage();
                    Files.deleteIfExists(dir.resolve(result.algorithm + ".out"));
                } finally {
                    activeProcess = null;
                    result.logAvailable = Files.isRegularFile(dir.resolve(result.algorithm + ".log"));
                }
                result.runtimeSeconds = (System.nanoTime() - start) / 1e9;
                save(session);
            }
            session.status = session.results.stream().allMatch(r -> r.status.equals("complete")) ? "complete" : "partial";
            save(session);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            session.status = "failed";
            try { save(session); } catch (IOException ignored) { }
        }
    }

    static String workerAlgorithm(String algorithm) {
        return algorithm.equals("local-search") ? "greedy-ls" : algorithm;
    }

    static boolean allowedFile(String file) {
        return file.equals("input.in") || ALGORITHMS.stream().anyMatch(a ->
                List.of("out", "log", "metrics.csv", "ls-trace.csv", "ocag-trace.csv")
                        .stream().anyMatch(suffix -> file.equals(a + "." + suffix)));
    }

    /** Read the runner's two-line numeric metrics format; the dataset is always input.in. */
    static Map<String, String> readMetrics(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.size() != 2) throw new IOException("Expected one solver metrics row.");
        String[] headers = lines.get(0).split(",", -1), values = lines.get(1).split(",", -1);
        if (headers.length != values.length) throw new IOException("Malformed solver metrics.");
        Map<String, String> metrics = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) metrics.put(headers[i], values[i]);
        if (!metrics.containsKey("internal_final_score") || !metrics.containsKey("algorithm")) {
            throw new IOException("Missing solver score or algorithm.");
        }
        return metrics;
    }

    /** Validate dimensions before the parser allocates arrays, then check every token. */
    static void validateInput(Path input) throws IOException {
        try (Reader reader = Files.newBufferedReader(input)) {
            StreamTokenizer tokens = new StreamTokenizer(reader);
            int v = number(tokens,1,10000), e = number(tokens,1,1000), r = number(tokens,1,1000000);
            int c = number(tokens,1,1000); number(tokens,1,500000);
            for (int i=0;i<v;i++) number(tokens,1,1000);
            for (int i=0;i<e;i++) {
                int dc = number(tokens,2,4000), k = number(tokens,0,c);
                Set<Integer> seen = new HashSet<>();
                for (int j=0;j<k;j++) {
                    if (!seen.add(number(tokens,0,c-1))) throw new IOException("Duplicate cache connection.");
                    // Synthetic opportunity-cost cases may exceed the official 500 ms cache bound.
                    number(tokens,1,dc-1);
                }
            }
            for (int i=0;i<r;i++) { number(tokens,0,v-1); number(tokens,0,e-1); number(tokens,1,10000); }
            if (tokens.nextToken()!=StreamTokenizer.TT_EOF) throw new IOException("Unexpected trailing data.");
        }
    }
    private static int number(StreamTokenizer tokens,int min,int max) throws IOException {
        if (tokens.nextToken()!=StreamTokenizer.TT_NUMBER || tokens.nval!=Math.rint(tokens.nval)
                || tokens.nval<min || tokens.nval>max) throw new IOException("Expected integer in ["+min+", "+max+"].");
        return (int)tokens.nval;
    }
    private Path directory(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        return root.resolve(id);
    }
    // Windows readers can hold a handle that prevents an atomic file replacement.
    private synchronized Session read(String id) throws IOException {
        Path state = directory(id).resolve("session.json");
        if (!Files.exists(state)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return mapper.readValue(state.toFile(), Session.class);
    }
    synchronized void save(Session session) throws IOException {
        Path dir = directory(session.id), temporary = dir.resolve("session.tmp");
        mapper.writeValue(temporary.toFile(),session);
        Files.move(temporary,dir.resolve("session.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    @PreDestroy public void close() {
        executor.shutdownNow();
        Process process = activeProcess;
        if (process != null) process.destroyForcibly();
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Session {
        public String id, filename, createdAt, status = "queued";
        public Summary summary;
        public List<Result> results = new ArrayList<>();
    }
    public record Summary(int videos,int endpoints,int caches,int capacity,long totalRequests,
            long uniqueDemands,long connections,long contributions,long bytes) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        public String algorithm, status = "pending", error, preview;
        public Long score;
        public int nonEmptyCaches;
        public double runtimeSeconds;
        public boolean logAvailable;
        public Map<String, String> metrics = new LinkedHashMap<>();
        public List<String> artifacts = new ArrayList<>();
    }
}
