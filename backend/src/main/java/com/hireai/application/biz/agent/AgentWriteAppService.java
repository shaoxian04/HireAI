package com.hireai.application.biz.agent;

import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates agent WRITE use cases. {@code register} creates an Agent + version v1 in
 * PENDING_VERIFICATION and returns the new agent id; {@code activate} transitions an owned
 * agent to ACTIVE. Owner identity is supplied by the caller (derived server-side from the
 * JWT, Invariant #5); activate enforces an explicit owner check.
 */
@Validated
public interface AgentWriteAppService {

    UUID register(@NonNull AgentRegisterInfo registerInfo);

    void activate(@NonNull UUID agentId, @NonNull UUID ownerId);
}
