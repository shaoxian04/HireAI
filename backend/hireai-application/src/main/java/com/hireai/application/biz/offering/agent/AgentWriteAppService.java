package com.hireai.application.biz.offering.agent;

import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PricingUpdateInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates agent WRITE use cases. {@code register} creates an Agent + version v1 in
 * PENDING_VERIFICATION and returns the new agent id; {@code activate} transitions an owned
 * agent to ACTIVE; {@code updatePricing} updates the commercials of the current version
 * in place. Owner identity is supplied by the caller (derived server-side from the JWT,
 * Invariant #5); all mutating operations enforce an explicit owner check.
 */
@Validated
public interface AgentWriteAppService {

    UUID register(@NonNull AgentRegisterInfo registerInfo);

    void activate(@NonNull UUID agentId, @NonNull UUID ownerId);

    /**
     * Updates the price, maxExecutionSeconds and capabilityCategories of the agent's
     * current version in place (spec §9 — no version history in this slice). The caller
     * must be the agent owner; throws {@code DomainException(NOT_FOUND)} otherwise.
     * outputSpec and webhookUrl are intentionally excluded — they are not editable here.
     *
     * @return the refreshed agent model reflecting the new commercials.
     */
    AgentModel updatePricing(@NonNull UUID agentId, @NonNull UUID ownerId,
                             @NonNull PricingUpdateInfo info);
}
