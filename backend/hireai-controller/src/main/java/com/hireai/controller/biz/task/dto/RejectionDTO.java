package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;

import java.time.Instant;

/**
 * The latest round of a task's rejection response path (#95): the Client's A/B/C rejection, the
 * Agent's response window and response, and — after a stand-by — the Client's decision window and
 * decision. {@code responseSilent}/{@code decisionSilent} say the outcome came from a window closing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RejectionDTO(
        int round,
        String reasonCategory,
        String reason,
        Instant rejectedAt,
        Instant responseDeadline,
        boolean revisionAllowed,
        String response,
        String responseReason,
        boolean responseSilent,
        Instant respondedAt,
        Instant decisionDeadline,
        String decision,
        boolean decisionSilent,
        Instant decidedAt
) {

    public static RejectionDTO from(RejectionModel r) {
        if (r == null) {
            return null;
        }
        return new RejectionDTO(r.round(), r.reasonCategory().name(), r.reason(), r.rejectedAt(),
                r.responseDeadline(), r.revisionAllowed(), r.response() == null ? null : r.response().name(),
                r.responseReason(), r.responseSilent(), r.respondedAt(), r.decisionDeadline(),
                r.decision() == null ? null : r.decision().name(), r.decisionSilent(), r.decidedAt());
    }
}
