package com.hireai.application.biz.agent;

import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates agent READ use cases. Enforces Invariant #5 (server-side identity +
 * ownership): an agent is only returned to its owner; otherwise NOT_FOUND, so existence is
 * not leaked across builders.
 */
@Validated
public interface AgentReadAppService {

    AgentModel getForOwner(@NonNull UUID agentId, @NonNull UUID ownerId);

    List<AgentModel> listForOwner(@NonNull UUID ownerId, @NonNull AgentQuery query);
}
