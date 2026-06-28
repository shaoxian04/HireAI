package com.hireai.application.biz.offering.agent;

import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PublishVersionInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates agent WRITE use cases. {@code register} creates an Agent + version v1 in
 * PENDING_VERIFICATION and returns the new agent id; {@code activate} transitions an owned
 * agent to ACTIVE; {@code publishNewVersion} publishes a new ACTIVE version superseding the
 * prior one. Owner identity is supplied by the caller (derived server-side from the JWT,
 * Invariant #5); all mutating operations enforce an explicit owner check.
 */
@Validated
public interface AgentWriteAppService {

    UUID register(@NonNull AgentRegisterInfo registerInfo);

    void activate(@NonNull UUID agentId, @NonNull UUID ownerId);

    /**
     * Publishes a NEW version of the agent's contract (supersedes the prior ACTIVE version, which is
     * retained as DEPRECATED history). The new version carries over the current version's outputSpec +
     * webhookUrl with the supplied commercials. The caller must be the agent owner; throws
     * {@code DomainException(NOT_FOUND)} otherwise. Returns the refreshed agent model.
     */
    AgentModel publishNewVersion(@NonNull UUID agentId, @NonNull UUID ownerId,
                                 @NonNull PublishVersionInfo info);
}
