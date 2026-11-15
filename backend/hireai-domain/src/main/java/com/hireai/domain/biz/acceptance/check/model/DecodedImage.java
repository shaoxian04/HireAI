package com.hireai.domain.biz.acceptance.check.model;

/**
 * The pixel facts the image checks need from a frozen deliverable, read by the {@code ImageDecoder}
 * port: its size, from the header, and the ARGB value of every pixel on its outermost ring (the
 * "background"). {@code borderArgb} is null when only the header was read (the image is larger than
 * the platform decodes), so the size is still judged and the background is left to review.
 */
public record DecodedImage(int width, int height, int[] borderArgb) {

    public DecodedImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("An image has a positive width and height");
        }
        if (borderArgb != null && borderArgb.length == 0) {
            throw new IllegalArgumentException("A decoded image has border pixels");
        }
        borderArgb = borderArgb == null ? null : borderArgb.clone();
    }

    /** The size alone: the header was read but the pixels were not. */
    public static DecodedImage headerOnly(int width, int height) {
        return new DecodedImage(width, height, null);
    }

    public boolean hasPixels() {
        return borderArgb != null;
    }

    @Override
    public int[] borderArgb() {
        return borderArgb == null ? null : borderArgb.clone();
    }
}
