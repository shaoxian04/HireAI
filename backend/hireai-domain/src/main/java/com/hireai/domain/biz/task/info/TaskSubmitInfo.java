package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Domain-layer carrier for the submit use case. Assembled by the controller from a
 * validated request plus the server-side client id; passed to the application layer.
 * {@code category} drives routing to a capability-matching agent. {@code deadlineSeconds} is the
 * optional Client deadline, counted from the submit (null = none; #83). {@code contract} is an explicit
 * Contract to freeze (#91, direct booking under the Client's terms); null = the output spec's equivalent.
 */
public record TaskSubmitInfo(UUID clientId, String title, String description,
                             Money budget, OutputSpec outputSpec, String category, Long deadlineSeconds,
                             Contract contract) {

    public TaskSubmitInfo(UUID clientId, String title, String description,
                          Money budget, OutputSpec outputSpec, String category, Long deadlineSeconds) {
        this(clientId, title, description, budget, outputSpec, category, deadlineSeconds, null);
    }

    public TaskSubmitInfo(UUID clientId, String title, String description,
                          Money budget, OutputSpec outputSpec, String category) {
        this(clientId, title, description, budget, outputSpec, category, null);
    }
}
