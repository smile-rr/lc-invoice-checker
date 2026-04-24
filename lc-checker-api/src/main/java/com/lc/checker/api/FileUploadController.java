package com.lc.checker.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Handles standalone file upload — stores the PDF in memory and returns a
 * {@code fileKey} that clients can use to reference the file in subsequent
 * calls.
 *
 * <p>V1: this endpoint is available but the primary flow is the combined
 * {@code POST /api/v1/lc-check} which accepts both LC text and invoice PDF
 * in a single multipart request.
 *
 * <p>V2 will extend this to return a {@code fileKey} that can be passed to
 * {@code /api/v1/lc-check} separately.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /**
     * In-memory file store keyed by UUID fileKey.
     * V2 will replace this with MinIO / S3-backed store.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, StoredFile> fileStore =
            new java.util.concurrent.ConcurrentHashMap<>();

    public record UploadResponse(String fileKey, String filename, long sizeBytes) {}

    public record StoredFile(String filename, byte[] bytes, String contentType) {}

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws MissingServletRequestPartException {
        if (file == null || file.isEmpty()) {
            throw new MissingServletRequestPartException("file");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.contains("pdf")) {
            log.warn("upload rejected: content-type={}", contentType);
            return ResponseEntity.badRequest().body(null);
        }

        // Reject oversized files before reading bytes.
        long maxBytes = 20 * 1024 * 1024; // 20 MB
        if (file.getSize() > maxBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(null);
        }

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.error("failed to read uploaded file", e);
            return ResponseEntity.internalServerError().build();
        }

        // Basic PDF magic-bytes check.
        if (bytes.length < 4 ||
                bytes[0] != 0x25 || // %
                bytes[1] != 0x50 || // P
                bytes[2] != 0x44 || // D
                bytes[3] != 0x46) { // F
            log.warn("upload rejected: not a PDF (magic bytes mismatch)");
            return ResponseEntity.badRequest().body(null);
        }

        String fileKey = UUID.randomUUID().toString();
        fileStore.put(fileKey, new StoredFile(file.getOriginalFilename(), bytes, contentType));

        log.info("file uploaded: key={} filename={} size={}",
                fileKey, file.getOriginalFilename(), bytes.length);

        return ResponseEntity.ok(new UploadResponse(fileKey, file.getOriginalFilename(), bytes.length));
    }

    /**
     * Download a previously uploaded file by its key.
     * Used by V2 to retrieve invoice PDFs stored via this endpoint.
     */
    @GetMapping("/{fileKey}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String fileKey) {
        StoredFile stored = fileStore.get(fileKey);
        if (stored == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", stored.filename());
        headers.setContentLength(stored.bytes().length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(
                        new java.io.ByteArrayInputStream(stored.bytes())));
    }
}
