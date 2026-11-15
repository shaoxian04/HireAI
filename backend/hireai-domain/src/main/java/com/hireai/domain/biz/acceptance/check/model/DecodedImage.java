package com.hireai.domain.biz.acceptance.check.model;

/**
 * The pixel facts the image checks need from a frozen deliverable, read by the {@code ImageDecoder}
 * port: its size, and the ARGB value of every pixel on its outermost ring (the "background").
 */
public record DecodedImage(int width, int height, int[] borderArgb) {

    public DecodedImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("An image has a positive width and height");
        }
        if (borderArgb == null || borderArgb.length == 0) {
            throw new IllegalArgumentException("An image has border pixels");
        }
        borderArgb = borderArgb.clone();
    }

    @Override
    public int[] borderArgb() {
        return borderArgb.clone();
    }
}
