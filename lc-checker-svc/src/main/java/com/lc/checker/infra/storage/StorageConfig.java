package com.lc.checker.infra.storage;

import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Wires the {@link S3Client} (pointed at MinIO) and the {@link InvoiceFileStore}
 * implementation. When {@code storage.minio.enabled=false} no S3 client is
 * created and the store falls through to a memory-only no-op.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties props) {
        StorageProperties.Minio cfg = props.minio();
        if (cfg == null || !cfg.enabled()) {
            log.info("storage.minio.enabled=false — building stub S3 client (no I/O)");
        }
        Duration timeout = Duration.ofSeconds(Math.max(1, cfg == null ? 3 : cfg.requestTimeoutSeconds()));
        return S3Client.builder()
                .endpointOverride(URI.create(cfg == null ? "http://localhost:9000" : cfg.endpoint()))
                .region(Region.of(cfg == null ? "us-east-1" : cfg.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                cfg == null ? "minioadmin" : cfg.accessKey(),
                                cfg == null ? "minioadmin" : cfg.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(cfg == null || cfg.pathStyle())
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(timeout)
                        .socketTimeout(timeout))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout)
                        .build())
                .build();
    }

    @Bean
    public InvoiceFileStore invoiceFileStore(S3Client s3, StorageProperties props,
                                              io.micrometer.tracing.Tracer tracer) {
        StorageProperties.Minio cfg = props.minio();
        if (cfg == null || !cfg.enabled()) {
            log.warn("MinIO disabled — InvoiceFileStore is a no-op; sessions will not survive restart");
            return new NoopInvoiceFileStore();
        }
        return new MinioFileStore(s3, cfg, tracer);
    }
}
