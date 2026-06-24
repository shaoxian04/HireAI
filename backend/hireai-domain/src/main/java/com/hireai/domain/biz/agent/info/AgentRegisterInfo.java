package com.hireai.domain.biz.agent.info;

import com.hireai.domain.biz.task.model.OutputSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Domain-layer carrier for the register use case. Assembled by the controller from a
 * validated request plus the server-side owner id; passed to the application layer.
 */
public record AgentRegisterInfo(UUID ownerId, String name, OutputSpec outputSpec,
                                List<String> capabilityCategories, String webhookUrl,
                                int maxExecutionSeconds, BigDecimal price) {
}
