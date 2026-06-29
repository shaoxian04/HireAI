package com.hireai.domain.biz.offering.agent.info;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carrier for publish-new-version: the new commercials (price / maxExecutionSeconds / categories).
 * outputSpec and webhookUrl are deliberately excluded — they carry over from the current version
 * (re-declaring the webhook/output contract in a new version is deferred, spec §7).
 */
public record PublishVersionInfo(BigDecimal price, int maxExecutionSeconds,
                                 List<String> capabilityCategories) {
}
