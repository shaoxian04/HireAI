package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Domain-layer carrier for the submit use case. Assembled by the controller from a
 * validated request plus the server-side client id; passed to the application layer.
 * {@code category} drives routing to a capability-matching agent.
 */
public record TaskSubmitInfo(UUID clientId, String title, String description,
                             Money budget, OutputSpec outputSpec, String category) {
}
