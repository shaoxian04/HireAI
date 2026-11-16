package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.acceptance.check.model.DecodedImage;
import com.hireai.domain.biz.acceptance.check.service.ImageDecoder;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * The {@link ImageDecoder} port on the JDK's ImageIO: PNG, JPEG, GIF and BMP decode; anything else
 * (WebP, a PDF, garbage) is empty, so the pixel checks send the result to review.
 *
 * <p>Memory is bounded before any pixel is decoded. The header is read first; an image wider or
 * taller than the Template allows ({@link ImageGenerationTemplate#MAX_DIMENSION}), or whose edge
 * would take more than {@link #MAX_EDGE_BYTES} at its own bit depth and band count, is answered
 * with its size only ({@link DecodedImage#headerOnly}): the size check still runs on it, the
 * background is left to review. Otherwise only the edge is decoded, in two subsampled reads (the
 * top and bottom rows, then the left and right columns), so a read allocates at most two rows or
 * two columns, never the whole canvas.
 */
@Slf4j
@Component
public class ImageIoImageDecoder implements ImageDecoder {

    static final int MAX_DIMENSION = ImageGenerationTemplate.MAX_DIMENSION;
    /** The most the decoded edge of one image may take, at its own bytes per pixel. */
    static final long MAX_EDGE_BYTES = 4L * 1024 * 1024;

    @Override
    public Optional<DecodedImage> decode(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (in == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                // Not seek-forward-only: the edge is read in two passes over the same bytes.
                reader.setInput(in, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return Optional.empty();
                }
                long edgeBytes = 2L * (width + (long) height) * bytesPerPixel(reader);
                if (width > MAX_DIMENSION || height > MAX_DIMENSION || edgeBytes > MAX_EDGE_BYTES) {
                    log.info("Reading only the size of a {}x{} px image (limit {} px a side, {} edge bytes)",
                            width, height, MAX_DIMENSION, MAX_EDGE_BYTES);
                    return Optional.of(DecodedImage.headerOnly(width, height));
                }
                return Optional.of(new DecodedImage(width, height, edge(reader, width, height)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.info("Could not decode a delivered image: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Bytes per pixel as stored: bands times bits per sample, rounded up; 16 (RGBA, 32-bit) if unknown. */
    private static long bytesPerPixel(ImageReader reader) throws IOException {
        ImageTypeSpecifier type = reader.getRawImageType(0);
        if (type == null) {
            return 16;
        }
        long bits = 0;
        for (int band = 0; band < type.getNumBands(); band++) {
            bits += type.getBitsPerBand(band);
        }
        // Decoded pixels are converted to ARGB ints as they are read, so count at least 4 bytes.
        return Math.max(4, (bits + 7) / 8);
    }

    private static int[] edge(ImageReader reader, int w, int h) throws IOException {
        if (w <= 2 || h <= 2) {
            // Every pixel of so thin an image is on its edge, and there are at most 2 x 4096 of them.
            BufferedImage all = reader.read(0);
            return all.getRGB(0, 0, w, h, null, 0, w);
        }
        BufferedImage rows = read(reader, new Rectangle(0, 0, w, h), 1, h - 1);    // rows 0 and h-1
        BufferedImage cols = read(reader, new Rectangle(0, 1, w, h - 2), w - 1, 1); // columns 0 and w-1
        int[] border = new int[2 * w + 2 * (h - 2)];
        int i = 0;
        for (int y = 0; y < rows.getHeight(); y++) {
            for (int x = 0; x < rows.getWidth(); x++) {
                border[i++] = rows.getRGB(x, y);
            }
        }
        for (int y = 0; y < cols.getHeight(); y++) {
            for (int x = 0; x < cols.getWidth(); x++) {
                border[i++] = cols.getRGB(x, y);
            }
        }
        return border;
    }

    private static BufferedImage read(ImageReader reader, Rectangle region, int stepX, int stepY) throws IOException {
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);
        param.setSourceSubsampling(stepX, stepY, 0, 0);
        return reader.read(0, param);
    }
}
