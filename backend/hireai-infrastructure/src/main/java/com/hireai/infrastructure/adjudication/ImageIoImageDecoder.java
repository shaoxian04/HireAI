package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.acceptance.check.model.DecodedImage;
import com.hireai.domain.biz.acceptance.check.service.ImageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * The {@link ImageDecoder} port on the JDK's ImageIO: PNG, JPEG, GIF and BMP decode; anything else
 * (WebP, a PDF, garbage) is empty, so the pixel checks send the result to review. The header is
 * read first and an image over {@link #MAX_PIXELS} is never decoded, so a small file declaring an
 * enormous canvas cannot exhaust memory. Only the outermost ring of pixels is kept.
 */
@Slf4j
@Component
public class ImageIoImageDecoder implements ImageDecoder {

    /** 40 megapixels: far above anything the image_generation Template allows (4096 x 4096). */
    static final long MAX_PIXELS = 40_000_000L;

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
                reader.setInput(in, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) {
                    log.info("Not decoding an image of {}x{} px (limit {} px)", width, height, MAX_PIXELS);
                    return Optional.empty();
                }
                return Optional.of(ring(reader.read(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.info("Could not decode a delivered image: {}", e.toString());
            return Optional.empty();
        }
    }

    private static DecodedImage ring(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 2 || h <= 2) {
            // Every pixel of so thin an image is on its edge.
            return new DecodedImage(w, h, image.getRGB(0, 0, w, h, null, 0, w));
        }
        int[] border = new int[2 * w + 2 * (h - 2)];
        int i = 0;
        for (int x = 0; x < w; x++) {
            border[i++] = image.getRGB(x, 0);
            border[i++] = image.getRGB(x, h - 1);
        }
        for (int y = 1; y < h - 1; y++) {
            border[i++] = image.getRGB(0, y);
            border[i++] = image.getRGB(w - 1, y);
        }
        return new DecodedImage(w, h, border);
    }
}
