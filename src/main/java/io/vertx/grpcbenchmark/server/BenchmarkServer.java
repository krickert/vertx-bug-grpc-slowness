package io.vertx.grpcbenchmark.server;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.grpc.server.GrpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x gRPC Benchmark Server.
 * Starts an HTTP/2 server with gRPC enabled.
 */
public class BenchmarkServer {

    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkServer.class);
    
    public static final int DEFAULT_PORT = 9090;
    
    private final Vertx vertx;
    private final int port;
    private HttpServer httpServer;
    
    public BenchmarkServer(Vertx vertx) {
        this(vertx, DEFAULT_PORT);
    }
    
    public BenchmarkServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }
    
    /**
     * Starts the gRPC server with default HTTP/2 settings.
     * Note: The HTTP/2 flow control window size is NOT configurable in Vert.x
     * without significant code changes. This demonstrates the OOTB performance.
     */
    public void start() {
        // Create gRPC server
        GrpcServer grpcServer = GrpcServer.server(vertx);
        
        // Register our service implementation
        BenchmarkServiceImpl service = new BenchmarkServiceImpl();
        service.bindAll(grpcServer);
        
        // HTTP/2 server options - note: initial window size is not directly configurable!
        // The default HTTP/2 initial window size is 65535 bytes (64KB - 1)
        // This is the root cause of slowness with large payloads.
        HttpServerOptions options = new HttpServerOptions()
                .setPort(port)
                .setHost("localhost");
        
        // Create and start HTTP server with gRPC handler
        httpServer = vertx.createHttpServer(options);
        
        httpServer.requestHandler(grpcServer)
                .listen()
                .onSuccess(server -> {
                    LOG.info("Vert.x gRPC Benchmark Server started on port {}", server.actualPort());
                    LOG.info("HTTP/2 flow control window size: DEFAULT (64KB) - NOT CONFIGURABLE");
                })
                .onFailure(err -> {
                    LOG.error("Failed to start server", err);
                });
    }
    
    /**
     * Gets the actual port the server is listening on.
     * Useful when port 0 is specified for random port assignment.
     */
    public int getActualPort() {
        return httpServer != null ? httpServer.actualPort() : -1;
    }
    
    /**
     * Stops the server.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.close()
                    .onSuccess(v -> LOG.info("Server stopped"))
                    .onFailure(err -> LOG.error("Failed to stop server", err));
        }
    }
    
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        BenchmarkServer server = new BenchmarkServer(vertx);
        server.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            vertx.close();
        }));
    }
}
