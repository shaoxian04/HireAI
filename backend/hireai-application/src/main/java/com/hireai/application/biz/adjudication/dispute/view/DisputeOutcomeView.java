package com.hireai.application.biz.adjudication.dispute.view;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A dispute as one participant sees it (#96).
 *
 * @param taskTitle       the disputed task's title
 * @param price           the task's price (the Item price the fee caps are taken from)
 * @param yourSides       the viewer's side(s): CLIENT, BUILDER, or both on a self-booked task
 * @param awaiting        the sides that may still accept or appeal the proposed ruling
 * @param appealDeadline  while the ruling is proposed: when it is accepted on everyone's behalf
 * @param arbitrationCost the capped arbitration cost the loser bears
 * @param appealFee       the capped Appeal Fee the loser bears if the ruling is appealed
 * @param fees            how each fee was borne, once the dispute settled
 * @param clientReason    the Client's rejection text
 * @param agentResponse   the Agent's answer to the rejection (STAND_BY before arbitration), or null
 * @param agentReason     the reason the Agent gave for standing by, or null
 * @param agentSilent     true when the Agent stood by silently (its window lapsed)
 */
public record DisputeOutcomeView(DisputeModel dispute, String taskTitle, Money price, Set<DisputeParty> yourSides, Set<DisputeParty> awaiting,
                                 Instant appealDeadline, Money arbitrationCost, Money appealFee,
                                 List<DisputeFeeCharge> fees, String clientReason,
                                 AgentResponse agentResponse, String agentReason, boolean agentSilent) {
}
