package com.hireai.application.biz.adjudication.port;

/**
 * A DeliverySnapshot as the arbitration worker receives it (D24): the frozen bytes, inline as
 * base64, with what the platform judged them to be. {@code contentBase64} is null when the file is
 * larger than {@code hireai.arbitration.inline-deliverable-max-bytes}; the worker then judges the
 * metadata alone and says so. Multimodal arbitration (#99) reads image/PDF bytes from here.
 */
public record ArbitrationDeliverable(String sha256, String mediaType, long sizeBytes, String contentBase64) {
}
