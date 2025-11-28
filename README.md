# Vert.x gRPC Performance Benchmark

This project demonstrates the performance limitations of Vert.x gRPC with large payloads due to the default HTTP/2 flow control window size (64KB), and how to improve performance by configuring custom window sizes.

## Related Issue

- **Quarkus Issue**: https://github.com/quarkusio/quarkus/issues/51129
- **Problem**: Quarkus gRPC in unified mode is slow due to Vert.x's inability to configure HTTP/2 flow control window size

## The Issue

When using Vert.x gRPC (which underlies Quarkus unified gRPC server), the HTTP/2 flow control window size is set to the default 64KB. This limits throughput for large messages to approximately **5-10 MB/s**, whereas with a tuned Netty gRPC server (100MB window), throughput can reach **200+ MB/s**.

## Running the Benchmarks

### Basic Benchmark (Default Settings)
```bash
./gradlew test --tests GrpcBenchmarkTest
```

### Window Size Comparison Benchmark
```bash
./gradlew test --tests GrpcWindowSizeBenchmarkTest
```

### Run All Tests
```bash
./gradlew test
```

## Expected Results

The benchmark tests various payload sizes and demonstrates that:

1. **Small payloads (< 100KB)**: Performance is acceptable
2. **Large payloads (> 1MB)**: Performance degrades significantly due to flow control window limitations
3. **Custom window sizes**: Larger window sizes dramatically improve performance

### Sample Output (Default Settings)

```
BENCHMARK: 1 KB payload (1024 bytes)
  Throughput: ~0.3 MB/s

BENCHMARK: 10 KB payload (10240 bytes)
  Throughput: ~3 MB/s

BENCHMARK: 100 KB payload (102400 bytes)
  Throughput: ~16 MB/s

BENCHMARK: 1 MB payload (1048576 bytes)
  Throughput: ~27 MB/s

BENCHMARK: 10 MB payload (10485760 bytes)
  Throughput: ~7 MB/s  <-- NOTICE THE SLOWDOWN!

PARALLEL UPLOAD TEST: 5 x 1 MB = 5 MB total
  Throughput: ~6 MB/s
```

### Sample Output (Window Size Comparison)

The parameterized benchmark outputs CSV data comparing performance across different configurations:

```csv
window_size_bytes,concurrency,message_size_bytes,throughput_mbps,avg_latency_ms,total_bytes,total_time_seconds
65535,1,102400,17.34,5.631,307200,0.017
65535,1,1048576,28.41,35.201,3145728,0.106
65535,5,102400,17.84,27.368,1536000,0.082
65535,5,1048576,6.93,721.950,15728640,2.166
1048576,1,102400,90.24,1.082,307200,0.003
1048576,1,1048576,60.01,16.664,3145728,0.050
1048576,5,102400,49.08,9.948,1536000,0.030
1048576,5,1048576,7.11,703.618,15728640,2.111
```

**Key Finding**: With 1MB window size vs 64KB default, throughput improved by **400%+** for some configurations!

## Configuring the Parameterized Benchmark

The `GrpcWindowSizeBenchmarkTest` supports customization via system properties:

| Property | Description | Default |
|----------|-------------|---------|
| `benchmark.windowSizes` | Comma-separated window sizes (bytes) | `65535,1048576` |
| `benchmark.concurrencyLevels` | Comma-separated concurrency levels | `1,5` |
| `benchmark.messageSizes` | Comma-separated message sizes (bytes) | `102400,1048576` |
| `benchmark.warmupIterations` | Warmup iterations per test | `2` |
| `benchmark.testIterations` | Measured iterations per test | `3` |
| `benchmark.csvOutput` | Enable CSV output | `true` |

### Example: Custom Configuration

```bash
./gradlew test --tests GrpcWindowSizeBenchmarkTest \
  -Dbenchmark.windowSizes=65535,262144,1048576,4194304 \
  -Dbenchmark.concurrencyLevels=1,5,10 \
  -Dbenchmark.messageSizes=102400,1048576,10485760
```

This runs a full matrix of:
- 4 window sizes × 3 concurrency levels × 3 message sizes = 36 test combinations

## Key Observations

1. The throughput does not scale linearly with payload size
2. Larger payloads (10MB+) experience slower throughput than medium payloads (1MB)
3. This is directly caused by the HTTP/2 flow control window size limitation
4. **Increasing the window size dramatically improves throughput**

## How to Configure HTTP/2 Window Size in Vert.x

The `GrpcWindowSizeBenchmarkTest` demonstrates two approaches to configure HTTP/2 window size:

### Approach 1: Via Http2Settings (Recommended)

```java
// Create HTTP/2 settings with custom initial window size
Http2Settings http2Settings = new Http2Settings()
        .setInitialWindowSize(1048576); // 1MB

// Create HTTP server options with custom HTTP/2 settings
HttpServerOptions serverOptions = new HttpServerOptions()
        .setPort(9090)
        .setHost("localhost")
        .setInitialSettings(http2Settings);

// Create HTTP server
HttpServer httpServer = vertx.createHttpServer(serverOptions);
httpServer.requestHandler(grpcServer);
```

### Approach 2: Via Connection Handler (Per-Connection)

```java
// Set window size per connection
httpServer.connectionHandler(connection -> {
    connection.setWindowSize(1048576); // 1MB
});
```

### Quarkus Integration Note

While Quarkus does not expose HTTP/2 initial window size in its configuration, you can customize it via:
1. A CDI observer that intercepts server startup
2. Custom Vert.x configuration in your application

See the [Quarkus Issue #51129](https://github.com/quarkusio/quarkus/issues/51129) for more details.

## Technical Details

- **Vert.x Version**: 4.5.10
- **Default HTTP/2 Flow Control Window**: 64KB (65535 bytes)
- **Window Size CAN be changed**: Using `HttpServerOptions.setInitialSettings()` with custom `Http2Settings`

## Recommendations

For high-throughput gRPC with large payloads:
1. **Configure larger window size** using the approaches demonstrated in this project
2. Use the standard gRPC Netty server with configurable flow control window if Vert.x customization is not possible
3. Consider using streaming instead of unary calls for very large payloads
4. Request Quarkus team to expose HTTP/2 initial window size configuration

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── io/vertx/grpcbenchmark/
│   │       └── server/
│   │           ├── BenchmarkServer.java
│   │           └── BenchmarkServiceImpl.java
│   ├── proto/
│   │   └── benchmark.proto
│   └── resources/
│       └── logback.xml
└── test/
    ├── java/
    │   └── io/vertx/grpcbenchmark/
    │       ├── BenchmarkConfig.java         # Configuration for parameterized benchmarks
    │       ├── GrpcBenchmarkTest.java       # Basic benchmark with default settings
    │       └── GrpcWindowSizeBenchmarkTest.java  # Parameterized benchmark comparing window sizes
    └── resources/
        └── logback-test.xml
```

## Building

```bash
# Generate proto files
./gradlew generateProto

# Build the project
./gradlew build

# Run tests
./gradlew test
```
