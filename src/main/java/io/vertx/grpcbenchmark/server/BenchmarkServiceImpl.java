package io.vertx.grpcbenchmark.server;

import io.vertx.core.Future;
import io.vertx.grpcbenchmark.UploadBlobRequest;
import io.vertx.grpcbenchmark.UploadBlobResponse;
import io.vertx.grpcbenchmark.VertxBenchmarkServiceGrpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the BenchmarkService gRPC service.
 * Simply receives data and returns a success response.
 */
public class BenchmarkServiceImpl implements VertxBenchmarkServiceGrpcServer.BenchmarkServiceApi {

    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkServiceImpl.class);

    @Override
    public Future<UploadBlobResponse> uploadBlob(UploadBlobRequest request) {
        long bytesReceived = request.getContent().size();
        
        LOG.debug("Received upload: id={}, filename={}, bytes={}", 
                request.getUploadId(), request.getFilename(), bytesReceived);
        
        UploadBlobResponse response = UploadBlobResponse.newBuilder()
                .setSuccess(true)
                .setUploadId(request.getUploadId())
                .setBytesReceived(bytesReceived)
                .setMessage("Upload successful")
                .build();
        
        return Future.succeededFuture(response);
    }
}
