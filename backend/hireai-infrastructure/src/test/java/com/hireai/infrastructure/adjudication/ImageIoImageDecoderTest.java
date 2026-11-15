package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.acceptance.check.model.DecodedImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

class ImageIoImageDecoderTest {

    private final ImageIoImageDecoder decoder = new ImageIoImageDecoder();

    @Test
    void readsTheSizeAndOnlyTheEdgePixelsOfAPng() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 40, 30);
        g.setColor(Color.RED);
        g.fillRect(5, 5, 30, 20); // the subject, off the edge
        g.dispose();

        DecodedImage decoded = decoder.decode(encode(image, "png")).orElseThrow();

        assertThat(decoded.width()).isEqualTo(40);
        assertThat(decoded.height()).isEqualTo(30);
        assertThat(decoded.borderArgb()).hasSize(2 * 40 + 2 * 28);
        assertThat(Arrays.stream(decoded.borderArgb()).allMatch(p -> p == 0xFFFFFFFF)).isTrue();
    }

    @Test
    void aJpegDecodesToo() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        assertThat(decoder.decode(encode(image, "jpg"))).hasValueSatisfying(d -> assertThat(d.width()).isEqualTo(8));
    }

    @Test
    void bytesThatAreNotADecodableImageAreEmpty() {
        assertThat(decoder.decode("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(decoder.decode("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(decoder.decode(new byte[0])).isEmpty();
    }

    @Test
    void aSmallFileDeclaringAnEnormousCanvasIsNeverDecoded() {
        assertThat(decoder.decode(pngHeaderOnly(100_000, 100_000))).isEmpty();
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    /** A PNG signature and IHDR chunk, nothing else. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}).putInt(13).put(ihdr.array())
                .putInt((int) crc.getValue());
        return png.array();
    }
}
