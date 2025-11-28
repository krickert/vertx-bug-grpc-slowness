package io.vertx.grpcbenchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for parameterized gRPC benchmarks.
 * Allows testing different combinations of window sizes, concurrency levels, and message sizes.
 * 
 * <p>This configuration can be customized via system properties or programmatically.</p>
 * 
 * <p><b>System Properties:</b></p>
 * <ul>
 *   <li>{@code benchmark.windowSizes} - Comma-separated list of window sizes in bytes (e.g., "65535,262144,1048576")</li>
 *   <li>{@code benchmark.concurrencyLevels} - Comma-separated list of concurrency levels (e.g., "1,5,10")</li>
 *   <li>{@code benchmark.messageSizes} - Comma-separated list of message sizes in bytes (e.g., "1024,102400,1048576")</li>
 *   <li>{@code benchmark.warmupIterations} - Number of warmup iterations (default: 2)</li>
 *   <li>{@code benchmark.testIterations} - Number of test iterations (default: 3)</li>
 *   <li>{@code benchmark.csvOutput} - Whether to output results in CSV format (default: true)</li>
 * </ul>
 * 
 * <p><b>Example Usage:</b></p>
 * <pre>
 * ./gradlew test -Dbenchmark.windowSizes=65535,1048576 -Dbenchmark.messageSizes=1024,1048576
 * </pre>
 * 
 * <p><b>Related Issue:</b> <a href="https://github.com/quarkusio/quarkus/issues/51129">Quarkus Issue #51129</a></p>
 */
public class BenchmarkConfig {
    
    // Default HTTP/2 initial window size (64KB - 1)
    public static final int DEFAULT_WINDOW_SIZE = 65535;
    
    // Common window size values
    public static final int WINDOW_SIZE_256KB = 256 * 1024;
    public static final int WINDOW_SIZE_512KB = 512 * 1024;
    public static final int WINDOW_SIZE_1MB = 1024 * 1024;
    public static final int WINDOW_SIZE_4MB = 4 * 1024 * 1024;
    public static final int WINDOW_SIZE_16MB = 16 * 1024 * 1024;
    
    // Common message sizes
    public static final int MSG_SIZE_1KB = 1024;
    public static final int MSG_SIZE_10KB = 10 * 1024;
    public static final int MSG_SIZE_100KB = 100 * 1024;
    public static final int MSG_SIZE_1MB = 1024 * 1024;
    public static final int MSG_SIZE_10MB = 10 * 1024 * 1024;
    
    private final List<Integer> windowSizes;
    private final List<Integer> concurrencyLevels;
    private final List<Integer> messageSizes;
    private final int warmupIterations;
    private final int testIterations;
    private final boolean csvOutput;
    
    /**
     * Creates a default configuration for quick testing.
     */
    public BenchmarkConfig() {
        this(parseIntList(System.getProperty("benchmark.windowSizes", "65535,1048576")),
             parseIntList(System.getProperty("benchmark.concurrencyLevels", "1,5")),
             parseIntList(System.getProperty("benchmark.messageSizes", "102400,1048576")),
             Integer.getInteger("benchmark.warmupIterations", 2),
             Integer.getInteger("benchmark.testIterations", 3),
             Boolean.parseBoolean(System.getProperty("benchmark.csvOutput", "true")));
    }
    
    /**
     * Creates a custom configuration with specified parameters.
     * 
     * @param windowSizes List of HTTP/2 window sizes to test (in bytes)
     * @param concurrencyLevels List of concurrent request counts to test
     * @param messageSizes List of message payload sizes to test (in bytes)
     * @param warmupIterations Number of warmup iterations before measuring
     * @param testIterations Number of measured test iterations
     * @param csvOutput Whether to output results in CSV format
     */
    public BenchmarkConfig(List<Integer> windowSizes, List<Integer> concurrencyLevels,
                          List<Integer> messageSizes, int warmupIterations,
                          int testIterations, boolean csvOutput) {
        this.windowSizes = new ArrayList<>(windowSizes);
        this.concurrencyLevels = new ArrayList<>(concurrencyLevels);
        this.messageSizes = new ArrayList<>(messageSizes);
        this.warmupIterations = warmupIterations;
        this.testIterations = testIterations;
        this.csvOutput = csvOutput;
    }
    
    public List<Integer> getWindowSizes() {
        return windowSizes;
    }
    
    public List<Integer> getConcurrencyLevels() {
        return concurrencyLevels;
    }
    
    public List<Integer> getMessageSizes() {
        return messageSizes;
    }
    
    public int getWarmupIterations() {
        return warmupIterations;
    }
    
    public int getTestIterations() {
        return testIterations;
    }
    
    public boolean isCsvOutput() {
        return csvOutput;
    }
    
    /**
     * Gets the total number of test combinations.
     */
    public int getTotalCombinations() {
        return windowSizes.size() * concurrencyLevels.size() * messageSizes.size();
    }
    
    /**
     * Returns a human-readable description of a window size.
     */
    public static String formatWindowSize(int windowSize) {
        if (windowSize >= 1024 * 1024) {
            return (windowSize / (1024 * 1024)) + "MB";
        } else if (windowSize >= 1024) {
            return (windowSize / 1024) + "KB";
        } else {
            return windowSize + "B";
        }
    }
    
    /**
     * Returns a human-readable description of a message size.
     */
    public static String formatMessageSize(int messageSize) {
        return formatWindowSize(messageSize);
    }
    
    /**
     * Parses a comma-separated list of integers from a string.
     */
    private static List<Integer> parseIntList(String value) {
        List<Integer> result = new ArrayList<>();
        if (value != null && !value.isEmpty()) {
            for (String part : value.split(",")) {
                result.add(Integer.parseInt(part.trim()));
            }
        }
        return result;
    }
    
    /**
     * Builder for creating custom BenchmarkConfig instances.
     */
    public static class Builder {
        private List<Integer> windowSizes = new ArrayList<>();
        private List<Integer> concurrencyLevels = new ArrayList<>();
        private List<Integer> messageSizes = new ArrayList<>();
        private int warmupIterations = 2;
        private int testIterations = 3;
        private boolean csvOutput = true;
        
        public Builder addWindowSize(int size) {
            windowSizes.add(size);
            return this;
        }
        
        public Builder addConcurrencyLevel(int level) {
            concurrencyLevels.add(level);
            return this;
        }
        
        public Builder addMessageSize(int size) {
            messageSizes.add(size);
            return this;
        }
        
        public Builder warmupIterations(int iterations) {
            this.warmupIterations = iterations;
            return this;
        }
        
        public Builder testIterations(int iterations) {
            this.testIterations = iterations;
            return this;
        }
        
        public Builder csvOutput(boolean output) {
            this.csvOutput = output;
            return this;
        }
        
        public BenchmarkConfig build() {
            // Set defaults if empty
            if (windowSizes.isEmpty()) {
                windowSizes.add(DEFAULT_WINDOW_SIZE);
                windowSizes.add(WINDOW_SIZE_1MB);
            }
            if (concurrencyLevels.isEmpty()) {
                concurrencyLevels.add(1);
                concurrencyLevels.add(5);
            }
            if (messageSizes.isEmpty()) {
                messageSizes.add(MSG_SIZE_100KB);
                messageSizes.add(MSG_SIZE_1MB);
            }
            return new BenchmarkConfig(windowSizes, concurrencyLevels, messageSizes,
                                       warmupIterations, testIterations, csvOutput);
        }
    }
    
    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public String toString() {
        return String.format("BenchmarkConfig{windowSizes=%s, concurrency=%s, messageSizes=%s, warmup=%d, iterations=%d}",
                windowSizes, concurrencyLevels, messageSizes, warmupIterations, testIterations);
    }
}
