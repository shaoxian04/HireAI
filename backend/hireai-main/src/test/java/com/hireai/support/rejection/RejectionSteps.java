package com.hireai.support.rejection;

import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.repository.TaskRepository;
import org.springframework.context.ApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * The route from a reviewed result to arbitration since #95 (spec D20): the Client rejects for
 * reason A/B/C, the Agent stands by its result, and the Client requests arbitration. Tests about
 * disputes, rulings and their settlement use this instead of a bare {@code reject}, which now only
 * waits for the Agent's response.
 */
public final class RejectionSteps {

    private RejectionSteps() {
    }

    public static void rejectThenArbitrate(ApplicationContext context, UUID taskId, UUID clientId,
                                           RejectReason reasonCategory, String reason) {
        context.getBean(TaskReviewAppService.class).reject(taskId, clientId, reasonCategory, reason);
        UUID versionId = context.getBean(TaskRepository.class).findById(taskId).orElseThrow().agentVersionId();
        // The token the rejection notice would carry: issued for this task and its assigned version.
        String token = context.getBean(DispatchTokenService.class).issue(taskId, versionId,
                context.getBean(Clock.class).instant().plus(Duration.ofHours(2)));
        RejectionAppService rejection = context.getBean(RejectionAppService.class);
        rejection.respond(taskId, token,
                new AgentRejectionResponseInfo(AgentResponse.STAND_BY, "the result meets the spec", null, null));
        rejection.requestArbitration(taskId, clientId);
    }
}
