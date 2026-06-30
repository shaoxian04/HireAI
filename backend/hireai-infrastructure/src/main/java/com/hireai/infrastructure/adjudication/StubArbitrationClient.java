package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Synchronous deterministic arbitration adapter for tests. Active in the {@code test} profile only;
 * production uses {@code RabbitArbitrationClient} (async). The reason→category mapping is an
 * arbitrary-but-fixed fixture so all three settlement branches are reachable in integration tests.
 */
@Component
@Profile("test")
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
