package com.hireai.domain.biz.acceptance.delivery;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveredMediaType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform judges what a file is from its bytes, not only from the Agent's Content-Type. */
class DeliveredMediaTypeTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
    private static final byte[] PDF = "%PDF-1.7\n%binary".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    private static final byte[] BINARY = {0, 1, 2, 3, (byte) 0xFE, (byte) 0xFF, 0, 7};

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recognisesTheBinaryFileTypesByTheirMagicBytesWhateverTheAgentDeclares() {
        assertThat(DeliveredMediaType.determine(null, PNG)).isEqualTo("image/png");
        assertThat(DeliveredMediaType.determine("image/jpeg", PNG)).isEqualTo("image/png");
        assertThat(DeliveredMediaType.determine("application/octet-stream", JPEG)).isEqualTo("image/jpeg");
        assertThat(DeliveredMediaType.determine("text/plain", PDF)).isEqualTo("application/pdf");
        assertThat(DeliveredMediaType.determine(null, ZIP)).isEqualTo("application/zip");
        assertThat(DeliveredMediaType.determine(null, WEBP)).isEqualTo("image/webp");
    }

    @Test
    void aDeclaredImageWhoseBytesAreNotAnImageIsNotThatImage() {
        assertThat(DeliveredMediaType.determine("image/png", text("<!DOCTYPE html><html></html>")))
                .isEqualTo("text/html");
        assertThat(DeliveredMediaType.determine("image/png", text("just words"))).isEqualTo("text/plain");
        assertThat(DeliveredMediaType.determine("application/pdf", BINARY)).isEqualTo("application/octet-stream");
    }

    @Test
    void textKeepsTheDeclaredTextTypeAndItsParametersAreIgnored() {
        assertThat(DeliveredMediaType.determine("text/csv; charset=utf-8", text("a,b\n1,2\n"))).isEqualTo("text/csv");
        assertThat(DeliveredMediaType.determine("Application/JSON", text("{\"a\":1}"))).isEqualTo("application/json");
        assertThat(DeliveredMediaType.determine(null, text("plain words"))).isEqualTo("text/plain");
    }

    @Test
    void htmlIsRecognisedEvenWhenDeclaredAsAnotherTextType() {
        assertThat(DeliveredMediaType.determine("text/plain", text("  \n<html><body>x</body></html>")))
                .isEqualTo("text/html");
    }

    @Test
    void aDeclaredTextTypeOverBinaryBytesIsOpaqueBinary() {
        assertThat(DeliveredMediaType.determine("text/csv", BINARY)).isEqualTo("application/octet-stream");
    }

    @Test
    void anUnverifiableBinaryTypeKeepsTheDeclaration() {
        assertThat(DeliveredMediaType.determine("video/mp4", BINARY)).isEqualTo("video/mp4");
        assertThat(DeliveredMediaType.determine(null, BINARY)).isEqualTo("application/octet-stream");
        assertThat(DeliveredMediaType.determine("not a media type", BINARY)).isEqualTo("application/octet-stream");
    }
}
