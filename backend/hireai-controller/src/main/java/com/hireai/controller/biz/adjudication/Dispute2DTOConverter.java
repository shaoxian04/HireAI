package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.view.DisputeOutcomeView;
import com.hireai.controller.biz.adjudication.dto.DisputeFeeDTO;
import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.biz.adjudication.dto.RulingDTO;
import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.model.DisputeModel;

import java.util.List;
import java.util.Set;

public final class Dispute2DTOConverter {

    private Dispute2DTOConverter() {
    }

    public static DisputeOutcomeDTO toDTO(DisputeOutcomeView view) {
        DisputeModel dispute = view.dispute();
        String effectiveCategory = dispute.effectiveRuling()
                .map(r -> r.category().name())
                .orElse(null);
        var rulings = dispute.rulings().stream()
                .map(r -> new RulingDTO(r.tier(), r.decidedBy().name(), r.category().name(),
                        r.rationale(), r.decidedAt()))
                .toList();
        var fees = view.fees().stream()
                .map(f -> new DisputeFeeDTO(f.type().name(), f.fullCost().value(), f.cappedAmount().value(),
                        f.clientCharged().value(), f.builderCharged().value(), f.platformCovered().value()))
                .toList();
        return new DisputeOutcomeDTO(dispute.id(), dispute.taskId(), view.taskTitle(), view.price().value(),
                dispute.status().name(),
                dispute.reasonCategory().name(), effectiveCategory, rulings,
                names(view.yourSides()), names(view.awaiting()),
                dispute.appealedBy() == null ? null : dispute.appealedBy().name(),
                view.appealDeadline(), view.arbitrationCost().value(), view.appealFee().value(), fees,
                view.clientReason(), view.agentResponse() == null ? null : view.agentResponse().name(),
                view.agentReason(), view.agentSilent());
    }

    /** CLIENT before BUILDER, so the order is stable for the UI. */
    private static List<String> names(Set<DisputeParty> parties) {
        return parties.stream().sorted().map(Enum::name).toList();
    }
}
