package com.lc.checker.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Verifies the configured bucket exists on startup, creating it if missing.
 * Failure is logged but never blocks application startup — when MinIO is
 * unreachable the file store falls through to the in-memory hot cache so the
 * pipeline keeps working.
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
            log.info("MinIO bucket '{}' exists at {}", cfg.bucket(), cfg.endpoint());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            // 404 surfaces here on some MinIO versions instead of NoSuchBucketException.
            if (e.statusCode() == 404) {
                createBucket();
            } else {
                log.warn("MinIO headBucket failed (status={}): {} — sessions will run memory-only",
                        e.statusCode(), e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("MinIO unreachable at {}: {} ({}) — sessions will run memory-only",
                    cfg.endpoint(), e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private void createBucket() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(cfg.bucket()).build());
            log.info("MinIO bucket '{}' created at {}", cfg.bucket(), cfg.endpoint());
        } catch (RuntimeException e) {
            log.warn("MinIO createBucket('{}') failed: {} — sessions will run memory-only",
                    cfg.bucket(), e.getMessage());
        }
    }
}
