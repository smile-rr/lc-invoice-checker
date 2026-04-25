package com.lc.checker.stage.extract.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders PDF pages to PNG images using Apache PDFBox 3.x (pure Java, no native deps).
 *
 * <p>{@code BufferedImage.TYPE_INT_RGB} output is compatible with Apple Silicon
 * (M1/M2 Mac) without translation. Each page is rendered at {@code scale × 72 DPI}
 * before encoding to PNG bytes.
 *
 * <p>On M1 MacBooks with limited RAM, use scale=1.0 or 1.5. For high-DPI quality
 * matching Docling/MiniRU rendering, scale=2.0 is acceptable.
 */
@Component
public class PdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderer.class);

    /**
     * Render all pages of a PDF to PNG byte arrays.
     *
     * @param pdfBytes raw PDF bytes
     * @param scale    DPI scale factor (1.5 = 108 DPI; 2.0 = 144 DPI)
     * @return one PNG byte[] per page, in page order
     */
    public List<byte[]> renderAllPages(byte[] pdfBytes, float scale) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF byte array is empty");
        }

        List<byte[]> pages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            log.debug("PDF opened: {} pages", pageCount);

            PDFRenderer renderer = new PDFRenderer(document);

            for (int i = 0; i < pageCount; i++) {
                // PDFBox 3.x: renderImageWithDPI returns BufferedImage
                BufferedImage img = renderer.renderImageWithDPI(i, 72f * scale);
                if (img == null) {
                    log.warn("PDFBox returned null image for page {}; skipping", i + 1);
                    continue;
                }

                byte[] pngBytes = toPng(img);
                img.flush();
                pages.add(pngBytes);
            }

            log.debug("Rendered {} pages at scale {}x", pages.size(), scale);
            return pages;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("password")) {
                throw new IllegalArgumentException("PDF is password-protected: " + e.getMessage(), e);
            }
            throw new IllegalArgumentException("Failed to decode PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Extract plain text from a PDF as a fallback when LLM extraction fails.
     *
     * @param pdfBytes raw PDF bytes
     * @return all text in the PDF, or empty string on failure
     */
    public String extractText(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            log.warn("Could not extract text from PDF: {}", e.getMessage());
            return "";
        }
    }

    private byte[] toPng(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("ImageIO PNG encoding failed", e);
        }
    }
}
