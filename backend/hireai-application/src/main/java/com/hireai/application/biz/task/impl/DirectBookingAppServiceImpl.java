package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final StorefrontRepository agentProfileRepository;
    private final TaskWriteAppService taskWriteAppService;
    /** The app's Clock bean: a booking's deadline is counted from the booking. */
    private final Clock clock;

    /**
     * Grace added to the Agent's max execution time, as routing stamps it (same property). NON-final
     * so {@code @RequiredArgsConstructor} skips it; the inline default applies in plain-{@code new}
     * unit tests.
     */
    @Value("${hireai.execution.grace-seconds:60}")
    private long executionGraceSeconds = 60;

    @Override
    public UUID book(DirectBookingInfo info) {
        AgentModel agent = agentRepository.findById(info.agentId())
                .orElseThrow(this::notFound);

        // Bookable = ACTIVE (agent state) + listed (storefront state). Each half is its owning
        // aggregate's own query; composing them across the two aggregates is app orchestration.
        // Both failures surface as NOT_FOUND so unlisted agents are indistinguishable from absent
        // ones (no existence leak — spec §6).
        if (!agent.isActive() || !isListed(agent.id())) {
            throw notFound();
        }

        // Pricing rule (budget >= price) is owned by the agent version.
        agent.currentVersion().assertAffordable(info.budget());
        assertFitsDeadline(agent, info.deadlineSeconds());

        // Adopt the agent's declared output_spec as the task's binding contract (Invariant #4).
        // The task category (stats/labels, rebook) is the client's choice when given, which the
        // agent must serve; otherwise the agent's first capability category.
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(
                info.clientId(), info.title(), info.description(), info.budget(),
                agent.currentVersion().outputSpec(), taskCategory(agent, info.category()),
                info.deadlineSeconds());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        log.info("Task {} direct-booked to agent {} (version {})",
                taskId, agent.id(), agent.currentVersionId());
        return taskId;
    }

    private static String taskCategory(AgentModel agent, String requested) {
        List<String> served = agent.currentVersion().capabilityCategories();
        if (requested == null) {
            // Safe: AgentVersionModel.create guarantees >= 1 category.
            return served.get(0);
        }
        if (!served.contains(requested)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "This agent does not serve category '" + requested + "'. It serves: "
                            + String.join(", ", served));
        }
        return requested;
    }

    /**
     * A booking never substitutes another Agent, so an Agent that can't finish within the Client's
     * deadline (now + its max execution time + grace) is refused up front (400), before anything is
     * frozen (#83).
     */
    private void assertFitsDeadline(AgentModel agent, Long deadlineSeconds) {
        if (deadlineSeconds == null) {
            return;
        }
        Instant now = clock.instant();
        int maxExecution = agent.currentVersion().maxExecutionSeconds();
        if (!ClientDeadline.after(now, deadlineSeconds).admits(maxExecution, now, executionGraceSeconds)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "This agent can take up to " + maxExecution + "s (plus " + executionGraceSeconds
                            + "s grace), so it can't finish within the " + deadlineSeconds + "s deadline");
        }
    }

    private boolean isListed(UUID agentId) {
        return agentProfileRepository.findByAgentId(agentId)
                .map(StorefrontModel::listed)
                .orElse(false);
    }

    private DomainException notFound() {
        return new DomainException(ResultCode.NOT_FOUND, "Agent not found");
    }
}
