package com.lc.checker.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Verifies the configured bucket exists and pre-warms the MinIO connection
 * on startup. A {@code listObjectsV2(maxKeys=1)} call is issued after the
 * bucket is confirmed — this establishes the TCP connection and warms up
 * the S3 client's internal connection pool before the first real request
 * arrives (whether from the dispatcher or a cold-path /invoice or /lc-raw call).
 *
 * <p>Failure is logged but never blocks application startup — when MinIO is
 * unreachable the file store falls through to the in-memory hot cache so the
 * pipeline keeps working. However, the startup health probe gives operators a
 * clear signal if MinIO is misconfigured.
 */
@Component
public class MinioBucketBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketBootstrap.class);

    private final S3Client s3;
    private final StorageProperties.Minio cfg;

    public MinioBucketBootstrap(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.cfg = props.minio();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        if (cfg == null || !cfg.enabled()) {
            log.info("MinIO disabled — skipping bucket bootstrap");
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(cfg.bucket()).build());
            log.info("MinIO bucket '{}' exists at {} — store enabled", cfg.bucket(), cfg.endpoint());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            // 404 surfaces here on some MinIO versions instead of NoSuchBucketException.
            if (e.statusCode() == 404) {
                createBucket();
            } else {
                log.warn("MinIO headBucket failed (status={}): {} — sessions will run memory-only",
                        e.statusCode(), e.getMessage());
                return;
            }
        } catch (RuntimeException e) {
            log.warn("MinIO unreachable at {}: {} ({}) — sessions will run memory-only",
                    cfg.endpoint(), e.getMessage(), e.getClass().getSimpleName());
            return;
        }
        preWarmConnection();
    }

    /**
     * Issues a cheap listObjectsV2 call to establish the TCP connection and
     * pre-warm the S3 client's connection pool. Without this, the first call
     * after a JVM restart may timeout while the TCP handshake completes.
     * Failures are logged but don't block startup.
     */
    private void preWarmConnection() {
        long t0 = System.currentTimeMillis();
        try {
            // maxKeys=1 keeps the response tiny; we only care about the network effect.
            s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(cfg.bucket())
                    .maxKeys(1)
                    .build());
            log.info("MinIO connection pre-warmed ({}ms)", System.currentTimeMillis() - t0);
        } catch (S3Exception e) {
            // 200 means reachable, any other status means something is off
            if (e.statusCode() != 200) {
                log.warn("MinIO pre-warm returned status {}: {}", e.statusCode(), e.getMessage());
            } else {
                log.info("MinIO connection pre-warmed ({}ms)", System.currentTimeMillis() - t0);
            }
        } catch (RuntimeException e) {
            log.warn("MinIO pre-warm failed: {} ({}) — first request may be slow",
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private void createBucket() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(cfg.bucket()).build());
            log.info("MinIO bucket '{}' created at {} — store enabled", cfg.bucket(), cfg.endpoint());
            preWarmConnection();
        } catch (RuntimeException e) {
            log.warn("MinIO createBucket('{}') failed: {} — sessions will run memory-only",
                    cfg.bucket(), e.getMessage());
        }
    }
}
