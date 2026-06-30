// StubArbitrationClient.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Synchronous deterministic arbitration adapter for Phase 2 (before the Python service exists).
 * The reason→category mapping is an arbitrary-but-fixed test fixture chosen so all three settlement
 * branches are reachable; the real LLM ruling arrives via {@code RabbitArbitrationClient} in Phase 3.
 * Active only when no other {@link ArbitrationGateway} bean (the Rabbit adapter) is present.
 */
@Component
@ConditionalOnMissingBean(name = "rabbitArbitrationClient")
public class StubArbitrationClient implements ArbitrationGateway {

    @Override
    public Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task) {
        RulingCategory category = switch (dispute.reasonCategory()) {
            case A_MISMATCH -> RulingCategory.NOT_FULFILLED;
            case B_FACTUAL -> RulingCategory.PARTIALLY_FULFILLED;
            case C_INCOMPLETE -> RulingCategory.FULFILLED;
            case D_CHANGED_MIND -> throw new IllegalStateException("D_CHANGED_MIND never opens a dispute");
        };
        return Optional.of(new RulingInfo(category, "stub ruling for " + dispute.reasonCategory()));
    }
}
