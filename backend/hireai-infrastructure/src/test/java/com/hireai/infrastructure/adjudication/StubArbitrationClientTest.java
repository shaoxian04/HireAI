// StubArbitrationClientTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StubArbitrationClientTest {

    private final StubArbitrationClient client = new StubArbitrationClient();

    private DisputeModel disputeWith(RejectReason reason) {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), reason, "corr");
    }

    @Test
    void mismatchRulesNotFulfilled() {
        Optional<RulingInfo> r = client.requestRuling(disputeWith(RejectReason.A_MISMATCH), null);
        assertThat(r).isPresent();
        assertThat(r.get().category()).isEqualTo(RulingCategory.NOT_FULFILLED);
        assertThat(r.get().rationale()).isNotBlank();
    }

    @Test
    void factualRulesPartiallyFulfilled() {
        assertThat(client.requestRuling(disputeWith(RejectReason.B_FACTUAL), null).orElseThrow().category())
                .isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
    }

    @Test
    void incompleteRulesFulfilled() {
        assertThat(client.requestRuling(disputeWith(RejectReason.C_INCOMPLETE), null).orElseThrow().category())
                .isEqualTo(RulingCategory.FULFILLED);
    }
}
