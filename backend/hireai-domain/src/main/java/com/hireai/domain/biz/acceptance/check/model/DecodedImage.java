package com.hireai.domain.biz.acceptance.check.model;

/**
 * The pixel facts the image checks need from a frozen deliverable, read by the {@code ImageDecoder}
 * port: its size, from the header, and the ARGB value of every pixel on its outermost ring (the
 * "background"). {@code borderArgb} is null when only the header was read; {@code pixels} says why:
 * <ul>
 *   <li>{@link Pixels#TOO_LARGE}: the image is larger than the platform decodes (beyond the
 *       Template's bound), so it is never decoded and the checks reject it;</li>
 *   <li>{@link Pixels#CORRUPT}: the header was readable but the pixels were not, so the file is not
 *       the image it claims to be and the pixel checks reject it (it goes back for rework).</li>
 * </ul>
 */
public record DecodedImage(int width, int height, int[] borderArgb, Pixels pixels) {

    /** Whether the edge pixels were read, and if not, why. */
    public enum Pixels { DECODED, TOO_LARGE, CORRUPT }

    public DecodedImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("An image has a positive width and height");
        }
        if (borderArgb != null && borderArgb.length == 0) {
            throw new IllegalArgumentException("A decoded image has border pixels");
        }
        if (pixels == null || (pixels == Pixels.DECODED) != (borderArgb != null)) {
            throw new IllegalArgumentException("Border pixels are present exactly when the image was decoded");
        }
        borderArgb = borderArgb == null ? null : borderArgb.clone();
    }

    /** A fully decoded edge. */
    public DecodedImage(int width, int height, int[] borderArgb) {
        this(width, height, borderArgb, Pixels.DECODED);
    }

    /** The size alone: the image is larger than the platform decodes. */
    public static DecodedImage headerOnly(int width, int height) {
        return new DecodedImage(width, height, null, Pixels.TOO_LARGE);
    }

    /** The size alone: the header was readable but the pixel data is broken. */
    public static DecodedImage corrupt(int width, int height) {
        return new DecodedImage(width, height, null, Pixels.CORRUPT);
    }

    public boolean hasPixels() {
        return borderArgb != null;
    }

    public boolean isCorrupt() {
        return pixels == Pixels.CORRUPT;
    }

    @Override
    public int[] borderArgb() {
        return borderArgb == null ? null : borderArgb.clone();
    }
}
