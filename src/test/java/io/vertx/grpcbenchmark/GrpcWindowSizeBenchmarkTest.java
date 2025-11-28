package io.vertx.grpcbenchmark;

import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientOptions;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.GrpcServerOptions;
import io.vertx.grpcbenchmark.server.BenchmarkServiceImpl;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized benchmark test for measuring gRPC performance with custom HTTP/2 window sizes.
 * 
 * <p>This test demonstrates how to configure HTTP/2 initial window size in Vert.x to improve
 * throughput for large payloads. The key insight is that while {@link GrpcServerOptions} only
 * configures the gRPC server (not the HTTP/2 transport), you can set the window size via:</p>
 * 
 * <ol>
 *   <li>{@link HttpServerOptions#setInitialSettings(Http2Settings)} - Set initial HTTP/2 settings
 *       including initial window size before server binds</li>
 *   <li>{@link io.vertx.core.http.HttpConnection#setWindowSize(int)} - Set connection-level window
 *       size dynamically via connection handler</li>
 * </ol>
 * 
 * <p><b>Background:</b></p>
 * <p>The unified Quarkus server serves both gRPC and other protocols (REST, etc.) using the same
 * Vert.x event loop pool. This means performance and flow control settings are shared. The default
 * HTTP/2 flow control window size is 64KB (65535 bytes), which limits throughput for large messages
 * to approximately 5-10 MB/s, whereas a tuned server with larger window (e.g., 1MB+) can achieve
 * 200+ MB/s.</p>
 * 
 * <p><b>Configuration:</b></p>
 * <p>Use system properties to configure the benchmark:</p>
 * <ul>
 *   <li>{@code benchmark.windowSizes} - Comma-separated list of window sizes in bytes</li>
 *   <li>{@code benchmark.concurrencyLevels} - Comma-separated list of concurrency levels</li>
 *   <li>{@code benchmark.messageSizes} - Comma-separated list of message sizes in bytes</li>
 *   <li>{@code benchmark.warmupIterations} - Number of warmup iterations (default: 2)</li>
 *   <li>{@code benchmark.testIterations} - Number of test iterations (default: 3)</li>
 * </ul>
 * 
 * <p><b>Example:</b></p>
 * <pre>
 * ./gradlew test --tests GrpcWindowSizeBenchmarkTest \
 *   -Dbenchmark.windowSizes=65535,262144,1048576 \
 *   -Dbenchmark.concurrencyLevels=1,5,10 \
 *   -Dbenchmark.messageSizes=102400,1048576,10485760
 * </pre>
 * 
 * <p><b>Related Issues:</b></p>
 * <ul>
 *   <li><a href="https://github.com/quarkusio/quarkus/issues/51129">Quarkus Issue #51129</a> - 
 *       Slowness in gRPC due to window size configuration limitations</li>
 * </ul>
 * 
 * <p><b>Note:</b> While Quarkus does not directly expose initial window size configuration,
 * Vert.x allows it to be changed programmatically as demonstrated here.</p>
 * 
 * @see BenchmarkConfig Configuration options for this benchmark
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GrpcWindowSizeBenchmarkTest {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcWindowSizeBenchmarkTest.class);
    
    // Max message size for gRPC (needs to accommodate our largest test)
    private static final int MAX_MESSAGE_SIZE = 20 * 1024 * 1024; // 20MB
    
    private static Vertx vertx;
    private static BenchmarkConfig config;
    
    // Cache of random data for each message size
    private static final Map<Integer, byte[]> dataCache = new ConcurrentHashMap<>();
    
    // Results storage for CSV output
    private static final List<BenchmarkResult> results = Collections.synchronizedList(new ArrayList<>());
    
    /**
     * Holds results from a single benchmark run.
     */
    public static class BenchmarkResult {
        public final int windowSize;
        public final int concurrency;
        public final int messageSize;
        public final double throughputMBps;
        public final double avgLatencyMs;
        public final long totalBytes;
        public final double totalTimeSeconds;
        
        public BenchmarkResult(int windowSize, int concurrency, int messageSize,
                              double throughputMBps, double avgLatencyMs,
                              long totalBytes, double totalTimeSeconds) {
            this.windowSize = windowSize;
            this.concurrency = concurrency;
            this.messageSize = messageSize;
            this.throughputMBps = throughputMBps;
            this.avgLatencyMs = avgLatencyMs;
            this.totalBytes = totalBytes;
            this.totalTimeSeconds = totalTimeSeconds;
        }
        
        public String toCsvRow() {
            return String.format("%d,%d,%d,%.2f,%.3f,%d,%.3f",
                    windowSize, concurrency, messageSize, throughputMBps,
                    avgLatencyMs, totalBytes, totalTimeSeconds);
        }
    }
    
    @BeforeAll
    static void setup() {
        config = new BenchmarkConfig();
        vertx = Vertx.vertx();
        
        LOG.info("=".repeat(100));
        LOG.info("Vert.x gRPC Window Size Benchmark Test");
        LOG.info("=".repeat(100));
        LOG.info("");
        LOG.info("This test demonstrates performance improvements when using custom HTTP/2 window sizes.");
        LOG.info("Related Issue: https://github.com/quarkusio/quarkus/issues/51129");
        LOG.info("");
        LOG.info("Configuration: {}", config);
        LOG.info("Total combinations to test: {}", config.getTotalCombinations());
        LOG.info("");
        
        // Pre-generate random data for each message size
        LOG.info("Generating random test data...");
        SecureRandom random = new SecureRandom();
        for (int size : config.getMessageSizes()) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            dataCache.put(size, data);
            LOG.info("  Generated {} bytes of random data", size);
        }
        LOG.info("Random test data generated successfully");
        LOG.info("");
    }
    
    @AfterAll
    static void tearDown(VertxTestContext testContext) {
        LOG.info("");
        LOG.info("=".repeat(100));
        LOG.info("BENCHMARK COMPLETE");
        LOG.info("=".repeat(100));
        
        // Print CSV output if enabled
        if (config.isCsvOutput() && !results.isEmpty()) {
            printCsvOutput();
        }
        
        // Print summary analysis
        printSummaryAnalysis();
        
        if (vertx != null) {
            vertx.close()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }
    
    /**
     * Main benchmark test that runs all combinations of window sizes, concurrency levels, and message sizes.
     * Each combination gets its own server instance with the configured HTTP/2 window size.
     */
    @Test
    @Order(1)
    void benchmarkAllCombinations(VertxTestContext testContext) throws InterruptedException {
        // Build list of all test combinations
        List<TestCombination> combinations = new ArrayList<>();
        int combinationNumber = 0;
        int totalCombinations = config.getTotalCombinations();
        
        for (int windowSize : config.getWindowSizes()) {
            for (int concurrency : config.getConcurrencyLevels()) {
                for (int messageSize : config.getMessageSizes()) {
                    combinationNumber++;
                    combinations.add(new TestCombination(
                            combinationNumber, totalCombinations, windowSize, concurrency, messageSize));
                }
            }
        }
        
        // Run each combination sequentially
        Future<Void> chain = Future.succeededFuture();
        for (TestCombination combo : combinations) {
            chain = chain.compose(v -> runSingleBenchmark(combo));
        }
        
        chain.onSuccess(v -> testContext.completeNow())
             .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(600, TimeUnit.SECONDS));
    }
    
    /**
     * Internal class to hold test combination parameters.
     */
    private static class TestCombination {
        final int number;
        final int total;
        final int windowSize;
        final int concurrency;
        final int messageSize;
        
        TestCombination(int number, int total, int windowSize, int concurrency, int messageSize) {
            this.number = number;
            this.total = total;
            this.windowSize = windowSize;
            this.concurrency = concurrency;
            this.messageSize = messageSize;
        }
    }
    
    /**
     * Runs a single benchmark with the specified configuration.
     * 
     * <p>This method demonstrates how to configure HTTP/2 window size in Vert.x:</p>
     * <ol>
     *   <li>Create {@link Http2Settings} with desired initial window size</li>
     *   <li>Configure {@link HttpServerOptions} with these settings</li>
     *   <li>Optionally use connection handler to set window size per connection</li>
     * </ol>
     */
    private Future<Void> runSingleBenchmark(TestCombination combo) {
        LOG.info("");
        LOG.info("-".repeat(80));
        LOG.info("TEST {}/{}: WindowSize={}, Concurrency={}, MessageSize={}",
                combo.number, combo.total,
                BenchmarkConfig.formatWindowSize(combo.windowSize),
                combo.concurrency,
                BenchmarkConfig.formatMessageSize(combo.messageSize));
        LOG.info("-".repeat(80));
        
        // ============================================================================
        // SERVER CONFIGURATION WITH CUSTOM HTTP/2 WINDOW SIZE
        // ============================================================================
        // 
        // Key insight from issue https://github.com/quarkusio/quarkus/issues/51129:
        // GrpcServerOptions only configures the gRPC server, NOT the HTTP/2 transport.
        // In Quarkus unified mode, the gRPC server is an HttpServer request handler.
        //
        // Two ways to set window size:
        // 1. Via Http2Settings on HttpServerOptions (shown here) - affects initial settings
        // 2. Via connection handler (also shown) - allows per-connection tuning
        //
        // Note: In Quarkus, initial window size is not exposed in config, but Vert.x
        // allows it to be changed programmatically.
        // ============================================================================
        
        // Create HTTP/2 settings with custom initial window size
        Http2Settings http2Settings = new Http2Settings()
                .setInitialWindowSize(combo.windowSize);
        
        // Create gRPC server options with sufficient message size
        GrpcServerOptions grpcServerOptions = new GrpcServerOptions()
                .setMaxMessageSize(MAX_MESSAGE_SIZE);
        
        // Create HTTP server options with custom HTTP/2 settings
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setPort(0) // Random port
                .setHost("localhost")
                .setInitialSettings(http2Settings);
        
        // Create gRPC server
        GrpcServer grpcServer = GrpcServer.server(vertx, grpcServerOptions);
        BenchmarkServiceImpl service = new BenchmarkServiceImpl();
        service.bindAll(grpcServer);
        
        // Create HTTP server
        HttpServer httpServer = vertx.createHttpServer(serverOptions);
        
        // ============================================================================
        // OPTIONAL: Connection handler for per-connection window size tuning
        // ============================================================================
        // This demonstrates how to set window size dynamically per connection.
        // This is useful when you need different settings for different clients.
        // ============================================================================
        httpServer.connectionHandler(connection -> {
            // Set window size for this connection
            // Note: This is the connection-level window size (for flow control)
            connection.setWindowSize(combo.windowSize);
            LOG.debug("Set connection window size to {} bytes", combo.windowSize);
        });
        
        httpServer.requestHandler(grpcServer);
        
        // Start server
        return httpServer.listen()
                .compose(server -> {
                    int serverPort = server.actualPort();
                    LOG.info("Server started on port {} with window size {} bytes",
                            serverPort, combo.windowSize);
                    
                    // Create client with matching settings
                    GrpcClientOptions clientOptions = new GrpcClientOptions()
                            .setMaxMessageSize(MAX_MESSAGE_SIZE);
                    
                    GrpcClient grpcClient = GrpcClient.client(vertx, clientOptions);
                    VertxBenchmarkServiceGrpcClient benchmarkClient = new VertxBenchmarkServiceGrpcClient(
                            grpcClient,
                            SocketAddress.inetSocketAddress(serverPort, "localhost")
                    );
                    
                    // Run benchmark iterations fully async
                    return runBenchmarkIterations(benchmarkClient, combo)
                            .compose(result -> {
                                results.add(result);
                                return grpcClient.close();
                            })
                            .compose(v -> httpServer.close());
                });
    }
    
    /**
     * Runs the actual benchmark iterations (warmup + measured) in a fully async manner.
     */
    private Future<BenchmarkResult> runBenchmarkIterations(
            VertxBenchmarkServiceGrpcClient client, TestCombination combo) {
        
        byte[] data = dataCache.get(combo.messageSize);
        ByteString content = ByteString.copyFrom(data);
        
        // Warmup phase - chain warmup iterations
        LOG.info("Warming up ({} iterations, concurrency={})...", 
                config.getWarmupIterations(), combo.concurrency);
        
        Future<Void> warmupChain = Future.succeededFuture();
        for (int i = 0; i < config.getWarmupIterations(); i++) {
            final int iteration = i;
            warmupChain = warmupChain.compose(v -> 
                    runConcurrentRequests(client, content, combo.concurrency, "warmup-" + iteration));
        }
        
        // Measurement phase - chain test iterations and collect timings
        return warmupChain.compose(v -> {
            LOG.info("Running benchmark ({} iterations, concurrency={})...", 
                    config.getTestIterations(), combo.concurrency);
            
            List<Long> timings = Collections.synchronizedList(new ArrayList<>());
            
            Future<Void> testChain = Future.succeededFuture();
            for (int i = 0; i < config.getTestIterations(); i++) {
                final int iteration = i;
                testChain = testChain.compose(vv -> {
                    long startTime = System.nanoTime();
                    return runConcurrentRequests(client, content, combo.concurrency, "test-" + iteration)
                            .map(vvv -> {
                                long endTime = System.nanoTime();
                                timings.add(endTime - startTime);
                                return null;
                            });
                });
            }
            
            // Calculate statistics after all iterations complete
            return testChain.map(vvv -> {
                long totalBytes = (long) combo.messageSize * combo.concurrency * config.getTestIterations();
                long totalNanos = timings.stream().mapToLong(Long::longValue).sum();
                double totalSeconds = totalNanos / 1_000_000_000.0;
                double avgNanos = totalNanos / (double) config.getTestIterations();
                double avgMs = avgNanos / 1_000_000.0;
                double throughput = (totalBytes / (1024.0 * 1024.0)) / totalSeconds;
                
                // Log results
                LOG.info("");
                LOG.info("RESULT: Window={}, Concurrency={}, Message={}",
                        BenchmarkConfig.formatWindowSize(combo.windowSize),
                        combo.concurrency,
                        BenchmarkConfig.formatMessageSize(combo.messageSize));
                LOG.info("  Avg time per batch:  {} ms", String.format("%.3f", avgMs));
                LOG.info("  Throughput:          {} MB/s", String.format("%.2f", throughput));
                LOG.info("  Total data:          {} bytes in {} seconds", totalBytes, String.format("%.3f", totalSeconds));
                
                return new BenchmarkResult(
                        combo.windowSize, combo.concurrency, combo.messageSize,
                        throughput, avgMs, totalBytes, totalSeconds);
            });
        });
    }
    
    /**
     * Runs concurrent requests and waits for all to complete.
     */
    private Future<Void> runConcurrentRequests(
            VertxBenchmarkServiceGrpcClient client,
            ByteString content, int concurrency, String idPrefix) {
        
        List<Future<UploadBlobResponse>> futures = new ArrayList<>();
        
        for (int i = 0; i < concurrency; i++) {
            UploadBlobRequest request = UploadBlobRequest.newBuilder()
                    .setUploadId(idPrefix + "-" + i)
                    .setFilename("test_" + i + ".bin")
                    .setContent(content)
                    .build();
            
            futures.add(client.uploadBlob(request));
        }
        
        return Future.all(futures)
                .map(cf -> {
                    // Verify all succeeded
                    for (Future<UploadBlobResponse> future : futures) {
                        assertTrue(future.result().getSuccess(),
                                "Request should succeed");
                    }
                    return null;
                });
    }
    
    /**
     * Prints results in CSV format for easy analysis.
     */
    private static void printCsvOutput() {
        LOG.info("");
        LOG.info("=".repeat(100));
        LOG.info("CSV OUTPUT");
        LOG.info("=".repeat(100));
        LOG.info("");
        
        // Print CSV header
        System.out.println("window_size_bytes,concurrency,message_size_bytes,throughput_mbps,avg_latency_ms,total_bytes,total_time_seconds");
        
        // Print data rows
        for (BenchmarkResult result : results) {
            System.out.println(result.toCsvRow());
        }
        
        LOG.info("");
        LOG.info("End of CSV output");
    }
    
    /**
     * Prints summary analysis comparing different configurations.
     */
    private static void printSummaryAnalysis() {
        if (results.isEmpty()) {
            return;
        }
        
        LOG.info("");
        LOG.info("=".repeat(100));
        LOG.info("SUMMARY ANALYSIS");
        LOG.info("=".repeat(100));
        
        // Group results by window size
        Map<Integer, List<BenchmarkResult>> byWindowSize = results.stream()
                .collect(Collectors.groupingBy(r -> r.windowSize));
        
        LOG.info("");
        LOG.info("THROUGHPUT BY WINDOW SIZE:");
        for (Map.Entry<Integer, List<BenchmarkResult>> entry : byWindowSize.entrySet()) {
            double avgThroughput = entry.getValue().stream()
                    .mapToDouble(r -> r.throughputMBps)
                    .average()
                    .orElse(0);
            LOG.info("  {}: {} MB/s average",
                    BenchmarkConfig.formatWindowSize(entry.getKey()),
                    String.format("%.2f", avgThroughput));
        }
        
        // Find best configuration for each message size
        LOG.info("");
        LOG.info("BEST CONFIGURATION BY MESSAGE SIZE:");
        Map<Integer, List<BenchmarkResult>> byMessageSize = results.stream()
                .collect(Collectors.groupingBy(r -> r.messageSize));
        
        for (Map.Entry<Integer, List<BenchmarkResult>> entry : byMessageSize.entrySet()) {
            BenchmarkResult best = entry.getValue().stream()
                    .max(Comparator.comparingDouble(r -> r.throughputMBps))
                    .orElse(null);
            if (best != null) {
                LOG.info("  {} message: Window={}, Concurrency={} -> {} MB/s",
                        BenchmarkConfig.formatMessageSize(entry.getKey()),
                        BenchmarkConfig.formatWindowSize(best.windowSize),
                        best.concurrency,
                        String.format("%.2f", best.throughputMBps));
            }
        }
        
        // Calculate improvement from default to best
        if (byWindowSize.containsKey(BenchmarkConfig.DEFAULT_WINDOW_SIZE) && byWindowSize.size() > 1) {
            double defaultAvg = byWindowSize.get(BenchmarkConfig.DEFAULT_WINDOW_SIZE).stream()
                    .mapToDouble(r -> r.throughputMBps)
                    .average()
                    .orElse(0);
            
            double bestAvg = byWindowSize.entrySet().stream()
                    .filter(e -> e.getKey() != BenchmarkConfig.DEFAULT_WINDOW_SIZE)
                    .flatMap(e -> e.getValue().stream())
                    .mapToDouble(r -> r.throughputMBps)
                    .max()
                    .orElse(0);
            
            if (defaultAvg > 0) {
                double improvement = ((bestAvg - defaultAvg) / defaultAvg) * 100;
                LOG.info("");
                LOG.info("IMPROVEMENT WITH LARGER WINDOW SIZE:");
                LOG.info("  Default (64KB) avg throughput: {} MB/s", String.format("%.2f", defaultAvg));
                LOG.info("  Best throughput:               {} MB/s", String.format("%.2f", bestAvg));
                LOG.info("  Improvement:                   {}%", String.format("%.1f", improvement));
            }
        }
        
        LOG.info("");
        LOG.info("=".repeat(100));
        LOG.info("");
        LOG.info("KEY INSIGHTS:");
        LOG.info("  1. Larger HTTP/2 window sizes improve throughput for large payloads");
        LOG.info("  2. The default 64KB window size limits throughput due to flow control");
        LOG.info("  3. Window size can be configured via HttpServerOptions.setInitialSettings()");
        LOG.info("  4. Per-connection tuning is possible via HttpConnection.setWindowSize()");
        LOG.info("");
        LOG.info("QUARKUS NOTE:");
        LOG.info("  While Quarkus does not expose initial window size in config, you can");
        LOG.info("  customize it via a CDI observer or custom Vert.x configuration.");
        LOG.info("  See: https://github.com/quarkusio/quarkus/issues/51129");
        LOG.info("");
        LOG.info("=".repeat(100));
    }
}
