package com.hireai.controller.biz.adjudication.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A dispute as the viewer sees it. {@code yourSides} is CLIENT and/or BUILDER; {@code awaiting} the
 * sides that may still accept or appeal the proposed ruling (#96). {@code arbitrationCost} and
 * {@code appealFee} are the capped amounts a loser bears; {@code fees} how each was borne once settled.
 */
public record DisputeOutcomeDTO(UUID disputeId, UUID taskId, String status, String reasonCategory,
                                String effectiveCategory, List<RulingDTO> rulings,
                                List<String> yourSides, List<String> awaiting, String appealedBy,
                                Instant appealDeadline, BigDecimal arbitrationCost, BigDecimal appealFee,
                                List<DisputeFeeDTO> fees, String clientReason,
                                String agentResponse, String agentReason, boolean agentSilent) {}
