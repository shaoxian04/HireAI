package com.hireai.domain.biz.task.item.info;

import java.time.Instant;

/**
 * An Item's input document as the Agent receives it (spec S4, D31): a signed download link scoped to
 * this one Item that expires at the execution deadline ({@code expiresAt}), and the SourceSnapshot's
 * hash, type, size and file name, so the Agent can verify what it fetched.
 */
public record DispatchSource(String url, String sha256, String mediaType, long sizeBytes, String filename,
                             Instant expiresAt) {
}
