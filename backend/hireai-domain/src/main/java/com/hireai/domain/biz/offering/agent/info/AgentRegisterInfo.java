package com.hireai.domain.biz.offering.agent.info;

import com.hireai.domain.biz.task.model.OutputSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Domain-layer carrier for the register use case. Assembled by the controller from a
 * validated request plus the server-side owner id; passed to the application layer.
 * {@code suggestedCategory} is the Builder's optional free-text proposal for a missing bucket
 * (stored for admins, never used for matching); null when absent.
 */
public record AgentRegisterInfo(UUID ownerId, String name, OutputSpec outputSpec,
                                List<String> capabilityCategories, String webhookUrl,
                                int maxExecutionSeconds, BigDecimal price, int maxConcurrent,
                                String suggestedCategory) {

    /** Without a suggested category (the optional "suggest a category" field left empty). */
    public AgentRegisterInfo(UUID ownerId, String name, OutputSpec outputSpec,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, BigDecimal price, int maxConcurrent) {
        this(ownerId, name, outputSpec, capabilityCategories, webhookUrl, maxExecutionSeconds, price,
                maxConcurrent, null);
    }
}
