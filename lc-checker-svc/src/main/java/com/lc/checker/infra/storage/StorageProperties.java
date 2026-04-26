package com.lc.checker.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage configuration. Currently only MinIO/S3-compatible object storage.
 * See {@code application.yml} → {@code storage.minio} and the env-var fallbacks
 * documented there ({@code MINIO_ENDPOINT}, {@code MINIO_BUCKET}, …).
 */
@ConfigurationProperties("storage")
public record StorageProperties(Minio minio) {

    public record Minio(
            boolean enabled,
            String endpoint,
            String bucket,
            String region,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            int requestTimeoutSeconds) {}
}
