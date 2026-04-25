package com.lc.checker.stage.extract.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders PDF pages to invoice-friendly JPEGs using Apache PDFBox 3.x.
 *
 * <p>Optimised for vision-LLM invoice extraction:
 * <ul>
 *   <li><b>200 DPI</b> — industry-standard OCR resolution; matches what
 *       Qwen-VL family models were trained on.</li>
 *   <li><b>Grayscale</b> — invoices are black-on-white; 8-bpp is ~3× smaller
 *       than RGB with no quality loss for text/numbers. Color stamps lose
 *       their red signal — flip {@code grayscale=false} when stamps matter.</li>
 *   <li><b>JPEG quality 0.85</b> — ~5× smaller body than PNG for visually
 *       equivalent invoice images. Both DashScope and OpenAI-compatible
 *       servers accept {@code data:image/jpeg;base64,…} URLs.</li>
 *   <li><b>Page cap</b> — defends against huge PDFs OOM-ing local LLMs.</li>
 *   <li><b>Long-edge clamp</b> — vision LLMs internally resize; sending
 *       bigger than ~2048 px wastes bandwidth and tokens.</li>
 * </ul>
 */
@Component
public class PdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderer.class);
    private static final float JPEG_QUALITY = 0.85f;

    /**
     * Render up to {@code maxPages} pages of a PDF to JPEG byte arrays.
     *
     * @param pdfBytes        raw PDF bytes
     * @param dpi             rasterisation DPI (200 is the standard OCR target)
     * @param maxPages        hard cap; pages beyond this are dropped
     * @param maxLongEdgePx   long-edge pixel cap; pages larger get downscaled
     * @param grayscale       true for 8-bpp grayscale, false for full RGB
     * @return one JPEG byte[] per page, in page order
     */
    public List<byte[]> renderAllPages(
            byte[] pdfBytes, int dpi, int maxPages, int maxLongEdgePx, boolean grayscale) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF byte array is empty");
        }

        List<byte[]> pages = new ArrayList<>();
        ImageType type = grayscale ? ImageType.GRAY : ImageType.RGB;

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            int renderCount = Math.min(totalPages, maxPages);
            if (totalPages > maxPages) {
                log.warn("PDF has {} pages; capping at {}", totalPages, maxPages);
            }

            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < renderCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, type);
                if (img == null) {
                    log.warn("PDFBox returned null image for page {}; skipping", i + 1);
                    continue;
                }

                BufferedImage clamped = clampLongEdge(img, maxLongEdgePx);
                byte[] jpegBytes = toJpeg(clamped);
                log.info("rendered page {}: {}x{} px, {} bytes",
                        i + 1, clamped.getWidth(), clamped.getHeight(), jpegBytes.length);

                if (clamped != img) clamped.flush();
                img.flush();
                pages.add(jpegBytes);
            }

            return pages;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("password")) {
                throw new IllegalArgumentException("PDF is password-protected: " + e.getMessage(), e);
            }
            throw new IllegalArgumentException("Failed to decode PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Extract plain text from a PDF — used for born-digital fast-path detection
     * and as a fallback when LLM extraction fails.
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

    /** Downscale so the longer edge ≤ {@code maxLongEdgePx}. No-op when within bounds. */
    private static BufferedImage clampLongEdge(BufferedImage src, int maxLongEdgePx) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxLongEdgePx) return src;

        double scale = (double) maxLongEdgePx / longEdge;
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage out = new BufferedImage(targetW, targetH, src.getType() == 0 ? BufferedImage.TYPE_BYTE_GRAY : src.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Encode to JPEG at explicit 0.85 quality (default ImageIO is ~0.75 — too soft for text). */
    private static byte[] toJpeg(BufferedImage img) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(img, null, null), param);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("ImageIO JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
    }
}
