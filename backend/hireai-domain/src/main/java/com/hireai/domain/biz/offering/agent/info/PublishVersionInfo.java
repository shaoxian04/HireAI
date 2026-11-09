package com.hireai.domain.biz.offering.agent.info;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carrier for publish-new-version: the new commercials (price / maxExecutionSeconds / categories).
 * outputSpec and webhookUrl are deliberately excluded — they carry over from the current version
 * (re-declaring the webhook/output contract in a new version is deferred, spec §7).
 * {@code suggestedCategory} is the optional "suggest a category" text (null when absent).
 */
public record PublishVersionInfo(BigDecimal price, int maxExecutionSeconds,
                                 List<String> capabilityCategories, String suggestedCategory) {

    /** Without a suggested category. */
    public PublishVersionInfo(BigDecimal price, int maxExecutionSeconds, List<String> capabilityCategories) {
        this(price, maxExecutionSeconds, capabilityCategories, null);
    }
}
