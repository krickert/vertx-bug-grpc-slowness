package io.vertx.grpcbenchmark;

import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpcbenchmark.server.BenchmarkServiceImpl;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark test for Vert.x gRPC performance with various payload sizes.
 * 
 * This test demonstrates the out-of-the-box (OOTB) performance of Vert.x gRPC
 * with the default HTTP/2 flow control window size (64KB).
 * 
 * Key findings from issue https://github.com/quarkusio/quarkus/issues/51129:
 * - The HTTP/2 flow control window size in Vert.x is NOT easily configurable
 * - Default window size of 64KB significantly limits throughput for large messages
 * - This limitation affects all applications using Vert.x HTTP/2 (including Quarkus unified gRPC)
 * 
 * The test uses random data to avoid compression artifacts that could give false results.
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GrpcBenchmarkTest {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcBenchmarkTest.class);
    
    // Payload sizes to test (in bytes)
    private static final int SIZE_1KB = 1024;
    private static final int SIZE_10KB = 10 * 1024;
    private static final int SIZE_100KB = 100 * 1024;
    private static final int SIZE_1MB = 1024 * 1024;
    private static final int SIZE_10MB = 10 * 1024 * 1024;
    
    // Number of iterations for each test
    private static final int WARMUP_ITERATIONS = 2;
    private static final int TEST_ITERATIONS = 3;
    
    private static Vertx vertx;
    private static HttpServer httpServer;
    private static GrpcClient grpcClient;
    private static VertxBenchmarkServiceGrpcClient benchmarkClient;
    private static int serverPort;
    
    // Pre-generated random data for each size
    private static byte[] data1KB;
    private static byte[] data10KB;
    private static byte[] data100KB;
    private static byte[] data1MB;
    private static byte[] data10MB;
    
    @BeforeAll
    static void setup(VertxTestContext testContext) {
        LOG.info("=".repeat(80));
        LOG.info("Vert.x gRPC Benchmark Test");
        LOG.info("Testing OOTB performance with default HTTP/2 flow control window (64KB)");
        LOG.info("=".repeat(80));
        
        // Generate random data for each size
        LOG.info("Generating random test data...");
        SecureRandom random = new SecureRandom();
        data1KB = generateRandomData(SIZE_1KB, random);
        data10KB = generateRandomData(SIZE_10KB, random);
        data100KB = generateRandomData(SIZE_100KB, random);
        data1MB = generateRandomData(SIZE_1MB, random);
        data10MB = generateRandomData(SIZE_10MB, random);
        LOG.info("Random test data generated successfully");
        
        vertx = Vertx.vertx();
        
        // Max message size: 20MB is sufficient for our largest test (10MB payload)
        // Using a reasonable limit rather than Integer.MAX_VALUE to avoid memory exhaustion
        int maxMessageSize = 20 * 1024 * 1024; // 20MB
        
        // Create gRPC server with increased max message size
        io.vertx.grpc.server.GrpcServerOptions grpcServerOptions = new io.vertx.grpc.server.GrpcServerOptions()
                .setMaxMessageSize(maxMessageSize);
        
        GrpcServer grpcServer = GrpcServer.server(vertx, grpcServerOptions);
        BenchmarkServiceImpl service = new BenchmarkServiceImpl();
        service.bindAll(grpcServer);
        
        // HTTP/2 server options with default settings
        // NOTE: Initial window size is NOT configurable here - this is the problem!
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setPort(0) // Random port
                .setHost("localhost");
        
        // Create HTTP server
        httpServer = vertx.createHttpServer(serverOptions);
        httpServer.requestHandler(grpcServer);
        
        httpServer.listen()
                .onSuccess(server -> {
                    serverPort = server.actualPort();
                    LOG.info("Server started on port {}", serverPort);
                    
                    // Create gRPC client with increased max message size
                    io.vertx.grpc.client.GrpcClientOptions grpcClientOptions = new io.vertx.grpc.client.GrpcClientOptions()
                            .setMaxMessageSize(maxMessageSize);
                    grpcClient = GrpcClient.client(vertx, grpcClientOptions);
                    benchmarkClient = new VertxBenchmarkServiceGrpcClient(
                            grpcClient, 
                            SocketAddress.inetSocketAddress(serverPort, "localhost")
                    );
                    
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
    
    @AfterAll
    static void tearDown(VertxTestContext testContext) {
        LOG.info("Shutting down...");
        
        Future<?> closeFuture = Future.succeededFuture();
        
        if (grpcClient != null) {
            closeFuture = closeFuture.compose(v -> grpcClient.close());
        }
        if (httpServer != null) {
            closeFuture = closeFuture.compose(v -> httpServer.close());
        }
        if (vertx != null) {
            closeFuture = closeFuture.compose(v -> vertx.close());
        }
        
        closeFuture
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    private static byte[] generateRandomData(int size, SecureRandom random) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }
    
    // ==================== Benchmark Tests ====================
    
    @Test
    @Order(1)
    void benchmark1KB(VertxTestContext testContext) throws InterruptedException {
        runBenchmark("1 KB", data1KB, testContext);
    }
    
    @Test
    @Order(2)
    void benchmark10KB(VertxTestContext testContext) throws InterruptedException {
        runBenchmark("10 KB", data10KB, testContext);
    }
    
    @Test
    @Order(3)
    void benchmark100KB(VertxTestContext testContext) throws InterruptedException {
        runBenchmark("100 KB", data100KB, testContext);
    }
    
    @Test
    @Order(4)
    void benchmark1MB(VertxTestContext testContext) throws InterruptedException {
        runBenchmark("1 MB", data1MB, testContext);
    }
    
    @Test
    @Order(5)
    void benchmark10MB(VertxTestContext testContext) throws InterruptedException {
        runBenchmark("10 MB", data10MB, testContext);
    }
    
    /**
     * Tests parallel uploads with 1MB payloads.
     * Total: 5 files x 1MB = 5MB
     * This demonstrates parallel upload performance.
     */
    @Test
    @Order(6)
    void benchmarkParallel5x1MB(VertxTestContext testContext) throws InterruptedException {
        LOG.info("");
        LOG.info("-".repeat(60));
        LOG.info("PARALLEL UPLOAD TEST: 5 x 1 MB = 5 MB total");
        LOG.info("-".repeat(60));
        
        int fileCount = 5;
        long totalBytes = (long) data1MB.length * fileCount;
        ByteString content = ByteString.copyFrom(data1MB);
        
        // Warmup
        LOG.info("Warming up ({} requests)...", WARMUP_ITERATIONS);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            UploadBlobRequest request = UploadBlobRequest.newBuilder()
                    .setUploadId("warmup-" + i)
                    .setFilename("warmup.bin")
                    .setContent(content)
                    .build();
            benchmarkClient.uploadBlob(request).toCompletionStage().toCompletableFuture().join();
        }
        
        // Build all requests
        List<UploadBlobRequest> requests = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            requests.add(UploadBlobRequest.newBuilder()
                    .setUploadId("parallel-" + i)
                    .setFilename("parallel_" + i + ".bin")
                    .setContent(content)
                    .build());
        }
        
        // Run parallel benchmark
        LOG.info("Starting parallel upload benchmark...");
        long startTime = System.nanoTime();
        
        // Type-safe approach using List instead of array
        List<Future<UploadBlobResponse>> futures = requests.stream()
                .map(req -> benchmarkClient.uploadBlob(req))
                .toList();
        
        Future.all(futures)
                .onSuccess(cf -> {
                    long endTime = System.nanoTime();
                    double seconds = (endTime - startTime) / 1_000_000_000.0;
                    double megabytes = totalBytes / (1024.0 * 1024.0);
                    double throughput = megabytes / seconds;
                    
                    LOG.info("");
                    LOG.info("PARALLEL RESULT: {} files x {} MB = {} MB", fileCount, data1MB.length / (1024 * 1024), (int) megabytes);
                    LOG.info("  Time:       {} seconds", String.format("%.3f", seconds));
                    LOG.info("  Throughput: {} MB/s", String.format("%.2f", throughput));
                    LOG.info("  Per-file:   {} ms average", String.format("%.3f", (seconds * 1000) / fileCount));
                    LOG.info("");
                    
                    // Verify all succeeded
                    for (Future<UploadBlobResponse> future : futures) {
                        assertTrue(future.result().getSuccess());
                    }
                    
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS));
    }
    
    private void runBenchmark(String sizeLabel, byte[] data, VertxTestContext testContext) throws InterruptedException {
        LOG.info("");
        LOG.info("-".repeat(60));
        LOG.info("BENCHMARK: {} payload ({} bytes)", sizeLabel, data.length);
        LOG.info("-".repeat(60));
        
        ByteString content = ByteString.copyFrom(data);
        
        // Warmup
        LOG.info("Warming up ({} iterations)...", WARMUP_ITERATIONS);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            UploadBlobRequest request = UploadBlobRequest.newBuilder()
                    .setUploadId("warmup-" + i)
                    .setFilename("warmup_" + sizeLabel + ".bin")
                    .setContent(content)
                    .build();
            benchmarkClient.uploadBlob(request).toCompletionStage().toCompletableFuture().join();
        }
        
        // Run benchmark
        LOG.info("Running benchmark ({} iterations)...", TEST_ITERATIONS);
        List<Long> timings = new ArrayList<>();
        
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            UploadBlobRequest request = UploadBlobRequest.newBuilder()
                    .setUploadId("test-" + i)
                    .setFilename("test_" + sizeLabel + ".bin")
                    .setContent(content)
                    .build();
            
            long startTime = System.nanoTime();
            UploadBlobResponse response = benchmarkClient.uploadBlob(request)
                    .toCompletionStage().toCompletableFuture().join();
            long endTime = System.nanoTime();
            
            assertTrue(response.getSuccess());
            assertEquals(data.length, response.getBytesReceived());
            
            timings.add(endTime - startTime);
        }
        
        // Calculate statistics
        long totalNanos = timings.stream().mapToLong(Long::longValue).sum();
        double avgNanos = totalNanos / (double) TEST_ITERATIONS;
        double avgMs = avgNanos / 1_000_000.0;
        
        long totalBytes = (long) data.length * TEST_ITERATIONS;
        double totalSeconds = totalNanos / 1_000_000_000.0;
        double throughput = (totalBytes / (1024.0 * 1024.0)) / totalSeconds;
        
        // Log results
        LOG.info("");
        LOG.info("RESULT: {} payload", sizeLabel);
        LOG.info("  Avg time per request: {} ms", String.format("%.3f", avgMs));
        LOG.info("  Throughput:           {} MB/s", String.format("%.2f", throughput));
        LOG.info("  Total data:           {} bytes in {} seconds", totalBytes, String.format("%.3f", totalSeconds));
        LOG.info("");
        
        testContext.completeNow();
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }
    
    /**
     * Summary test that prints final statistics.
     */
    @Test
    @Order(99)
    void printSummary() {
        LOG.info("");
        LOG.info("=".repeat(80));
        LOG.info("BENCHMARK SUMMARY");
        LOG.info("=".repeat(80));
        LOG.info("");
        LOG.info("This benchmark demonstrates Vert.x gRPC OOTB performance.");
        LOG.info("");
        LOG.info("KEY OBSERVATION:");
        LOG.info("  The HTTP/2 flow control window size (default 64KB) limits throughput");
        LOG.info("  for large payloads. This setting is NOT easily configurable in Vert.x.");
        LOG.info("");
        LOG.info("RELATED ISSUE:");
        LOG.info("  https://github.com/quarkusio/quarkus/issues/51129");
        LOG.info("");
        LOG.info("For high-throughput gRPC with large payloads, consider:");
        LOG.info("  - Using the standard gRPC Netty server (with configurable flow control)");
        LOG.info("  - Requesting Vert.x to expose HTTP/2 initial window size configuration");
        LOG.info("");
        LOG.info("=".repeat(80));
    }
}
