package com.lc.checker.extractor.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.jpedal.exception.PdfException;
import org.jpedal.exception.PdfSecurityException;
import org.jpedal.io.JPedalSettings;
import org.jpedal.io.PdfDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders PDF pages to PNG images using JPedal (pure Java, no native deps).
 *
 * <p>Output is {@code BufferedImage.TYPE_INT_RGB} — compatible with Apple Silicon
 * (M1/M2 Mac) without translation. Each page is scaled by {@code scale} before encoding
 * to PNG bytes.
 *
 * <p>On M1 MacBooks with limited RAM, use scale=1.0 or 1.5. For high-DPI
 * quality comparison with Docling/MiniRU rendering, scale=2.0 is acceptable.
 */
@Component
public class PdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderer.class);

    /**
     * Render all pages of a PDF to PNG byte arrays.
     *
     * @param pdfBytes raw PDF bytes
     * @param scale    DPI scale factor (1.5 = 1.5×, good quality/size balance for M1 RAM)
     * @return one PNG byte[] per page, in order
     */
    public List<byte[]> renderAllPages(byte[] pdfBytes, float scale) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF byte array is empty");
        }

        List<byte[]> pages = new ArrayList<>();

        try (PdfDecoder decoder = new PdfDecoder()) {
            // Disable popup warnings in logs
            decoder.setInt(JPedalSettings.LICENCE_KEY, 0);

            decoder.openPdfArrayFromInputStream(new ByteArrayInputStream(pdfBytes), null);

            int pageCount = decoder.getPageCount();
            log.debug("PDF opened: {} pages", pageCount);

            for (int i = 0; i < pageCount; i++) {
                // Page indices are 0-based in JPedal
                BufferedImage img = decoder.getPageAsImage(i);
                if (img == null) {
                    log.warn("JPedal returned null image for page {}; skipping", i + 1);
                    continue;
                }

                BufferedImage toEncode = img;
                if (scale != 1.0f) {
                    toEncode = scaleImage(img, scale);
                }

                byte[] pngBytes = toPng(toEncode);
                pages.add(pngBytes);

                if (toEncode != img) {
                    toEncode.flush();
                }
            }

            log.debug("Rendered {} pages at scale {}x", pages.size(), scale);
            return pages;

        } catch (PdfSecurityException e) {
            throw new IllegalArgumentException("PDF is password-protected: " + e.getMessage(), e);
        } catch (PdfException e) {
            throw new IllegalArgumentException("Failed to decode PDF: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read PDF bytes: " + e.getMessage(), e);
        }
    }

    private BufferedImage scaleImage(BufferedImage original, float scale) {
        int w = (int) (original.getWidth() * scale);
        int h = (int) (original.getHeight() * scale);
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        scaled.getGraphics().drawImage(
                original.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH),
                0, 0, w, h, null);
        return scaled;
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
