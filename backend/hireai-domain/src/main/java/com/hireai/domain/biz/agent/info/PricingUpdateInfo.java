package com.hireai.domain.biz.agent.info;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carrier for an in-place commercial update (price / maxExecutionSeconds / categories).
 * outputSpec and webhookUrl are deliberately excluded — they are not editable in this slice.
 */
public record PricingUpdateInfo(BigDecimal price, int maxExecutionSeconds,
                                List<String> capabilityCategories) {
}
