package com.lc.checker.infra.validation;

import com.lc.checker.stage.parse.Mt700Parser;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pre-pipeline request gate. Rejects obviously broken input before the MT700 parser
 * or extractor has to touch it. Anything harder than a shape-check lives in the
 * parser / extractor and flows through their own exception types.
 */
@Component
public class InputValidator {

    /** Max PDF size for V1; override via {@code validator.pdf-max-bytes} if needed. */
    private final long pdfMaxBytes;

    /** "%PDF" in ASCII. */
    private static final byte[] PDF_MAGIC = new byte[]{0x25, 0x50, 0x44, 0x46};

    /** Minimal sniff: at least one MT700-shaped tag anywhere in the text. */
    private static final Pattern TAG_SNIFF = Pattern.compile(":\\d{2}[A-Z]?:");

    public InputValidator(@Value("${validator.pdf-max-bytes:20971520}") long pdfMaxBytes) {
        this.pdfMaxBytes = pdfMaxBytes;
    }

    public void validateLcText(String lcText) {
        if (lcText == null || lcText.isBlank()) {
            throw new ValidationException("VALIDATION_FAILED", "lc_parsing", "LC text is empty");
        }
        if (!TAG_SNIFF.matcher(lcText).find()) {
            throw new ValidationException(
                    "VALIDATION_FAILED", "lc_parsing",
                    "LC text contains no MT700 tag (expected pattern :NN[L]: somewhere in the input)");
        }
        // Mandatory-tag check — delegate to the parser's canonical list for single source of truth.
        for (String tag : Mt700Parser.MANDATORY_TAGS) {
            if (!lcText.contains(":" + tag + ":")) {
                throw new ValidationException(
                        "VALIDATION_FAILED", "lc_parsing",
                        "Mandatory MT700 field :" + tag + ": missing from LC input");
            }
        }
    }

    public void validatePdf(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ValidationException("VALIDATION_FAILED", "invoice_upload", "PDF body is empty");
        }
        if (pdfBytes.length > pdfMaxBytes) {
            throw new ValidationException(
                    "PAYLOAD_TOO_LARGE", "invoice_upload",
                    "PDF size " + pdfBytes.length + " exceeds max " + pdfMaxBytes + " bytes");
        }
        if (pdfBytes.length < PDF_MAGIC.length
                || pdfBytes[0] != PDF_MAGIC[0]
                || pdfBytes[1] != PDF_MAGIC[1]
                || pdfBytes[2] != PDF_MAGIC[2]
                || pdfBytes[3] != PDF_MAGIC[3]) {
            throw new ValidationException(
                    "INVALID_FILE_TYPE", "invoice_upload",
                    "Uploaded file is not a PDF (magic bytes 0x25504446 expected)");
        }
    }

    /** Convenience for callers that hold raw bytes of the LC text. */
    public void validateLcBytes(byte[] bytes) {
        validateLcText(bytes == null ? null : new String(bytes, StandardCharsets.UTF_8));
    }
}
