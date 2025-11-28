# Vert.x gRPC Performance Benchmark

This project demonstrates the performance limitations of Vert.x gRPC with large payloads due to the default HTTP/2 flow control window size (64KB).

## Related Issue

- **Quarkus Issue**: https://github.com/quarkusio/quarkus/issues/51129
- **Problem**: Quarkus gRPC in unified mode is slow due to Vert.x's inability to configure HTTP/2 flow control window size

## The Issue

When using Vert.x gRPC (which underlies Quarkus unified gRPC server), the HTTP/2 flow control window size is set to the default 64KB. This limits throughput for large messages to approximately **5-10 MB/s**, whereas with a tuned Netty gRPC server (100MB window), throughput can reach **200+ MB/s**.

## Running the Benchmark

```bash
./gradlew test
```

## Expected Results

The benchmark tests various payload sizes and demonstrates that:

1. **Small payloads (< 100KB)**: Performance is acceptable
2. **Large payloads (> 1MB)**: Performance degrades significantly due to flow control window limitations

### Sample Output

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

## Key Observations

1. The throughput does not scale linearly with payload size
2. Larger payloads (10MB+) experience slower throughput than medium payloads (1MB)
3. This is directly caused by the HTTP/2 flow control window size limitation

## Technical Details

- **Vert.x Version**: 4.5.10
- **Default HTTP/2 Flow Control Window**: 64KB (65535 bytes)
- **Cannot be changed**: The `HttpServerOptions` and `GrpcServerOptions` do not expose HTTP/2 flow control window configuration

## Recommendations

For high-throughput gRPC with large payloads:
1. Use the standard gRPC Netty server with configurable flow control window
2. Request Vert.x team to expose HTTP/2 initial window size configuration
3. Consider using streaming instead of unary calls for very large payloads

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
    │       └── GrpcBenchmarkTest.java
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
