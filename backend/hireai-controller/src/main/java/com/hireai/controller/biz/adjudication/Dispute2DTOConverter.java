package com.hireai.controller.biz.adjudication;

import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.biz.adjudication.dto.RulingDTO;
import com.hireai.domain.biz.adjudication.model.DisputeModel;

public final class Dispute2DTOConverter {

    private Dispute2DTOConverter() {
    }

    public static DisputeOutcomeDTO toDTO(DisputeModel dispute) {
        String effectiveCategory = dispute.effectiveRuling()
                .map(r -> r.category().name())
                .orElse(null);
        var rulings = dispute.rulings().stream()
                .map(r -> new RulingDTO(r.tier(), r.decidedBy().name(), r.category().name(),
                        r.rationale(), r.decidedAt()))
                .toList();
        return new DisputeOutcomeDTO(dispute.id(), dispute.taskId(), dispute.status().name(),
                dispute.reasonCategory().name(), effectiveCategory, rulings);
    }
}
