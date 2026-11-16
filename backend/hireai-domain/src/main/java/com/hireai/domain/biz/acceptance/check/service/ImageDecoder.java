package com.hireai.domain.biz.acceptance.check.service;

import com.hireai.domain.biz.acceptance.check.model.DecodedImage;

import java.util.Optional;

/**
 * Port: reads a frozen deliverable's pixels for the image checks. Empty when the bytes are not an
 * image this platform can decode (WebP, for one), so a pixel check sends the result to review
 * instead of guessing. Implemented in infrastructure; never fetches anything.
 */
public interface ImageDecoder {

    Optional<DecodedImage> decode(byte[] content);
}
