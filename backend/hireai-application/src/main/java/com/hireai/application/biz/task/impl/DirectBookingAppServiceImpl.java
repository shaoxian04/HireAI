package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Direct booking orchestration. Validates that the target agent is ACTIVE and listed
 * (both failures surface as NOT_FOUND — existence is not leaked for unlisted agents,
 * per spec §6). Checks budget >= agent price. Adopts the agent's declared output_spec
 * as the task's binding contract (Hard Invariant #4) and its first capability category
 * for stats/labels. Delegates atomic submit + escrow freeze + pinned dispatch to the
 * write service (Hard Invariants #1 and #6).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirectBookingAppServiceImpl implements DirectBookingAppService {

    private final AgentRepository agentRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final TaskWriteAppService taskWriteAppService;

    @Override
    public UUID book(DirectBookingInfo info) {
        AgentModel agent = agentRepository.findById(info.agentId())
                .orElseThrow(this::notFound);

        // Bookable = ACTIVE + listed. Both failures surface as NOT_FOUND so unlisted agents
        // are indistinguishable from absent ones (no existence leak — spec §6).
        if (agent.status() != AgentStatus.ACTIVE || !isListed(agent.id())) {
            throw notFound();
        }

        // budget must be >= agent's price
        if (info.budget().value().compareTo(agent.currentVersion().pricing().price()) < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Budget " + info.budget() + " is below the agent's price "
                            + agent.currentVersion().pricing().price());
        }

        // Adopt the agent's declared output_spec as the task's binding contract (Invariant #4)
        // and its first capability category as the task category (stats/labels).
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(
                info.clientId(), info.title(), info.description(), info.budget(),
                agent.currentVersion().outputSpec(),
                // Safe: AgentVersionModel.create guarantees >= 1 category.
                agent.currentVersion().capabilityCategories().get(0));

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        log.info("Task {} direct-booked to agent {} (version {})",
                taskId, agent.id(), agent.currentVersionId());
        return taskId;
    }

    private boolean isListed(UUID agentId) {
        return agentProfileRepository.findByAgentId(agentId)
                .map(AgentProfileModel::listed)
                .orElse(false);
    }

    private DomainException notFound() {
        return new DomainException(ResultCode.NOT_FOUND, "Agent not found");
    }
}
