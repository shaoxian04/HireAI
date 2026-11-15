package com.hireai.support.dispute;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Since #96 a proposed ruling settles only once every side it goes against has accepted it (the
 * Client, the Builder, or both on a split). Tests about what a ruling settles to use this instead of
 * a lone Client {@code acceptRuling}, which no longer settles a ruling against the Builder.
 */
public final class RulingSteps {

    private RulingSteps() {
    }

    public static void acceptAsEverySide(ApplicationContext context, UUID disputeId) {
        DisputeModel dispute = context.getBean(DisputeRepository.class).findById(disputeId).orElseThrow();
        TaskModel task = context.getBean(TaskRepository.class).findById(dispute.taskId()).orElseThrow();
        DisputeAppService disputes = context.getBean(DisputeAppService.class);
        for (DisputeParty side : dispute.partiesWhoMayAppeal()) {
            UUID userId = side == DisputeParty.CLIENT
                    ? task.clientId()
                    : context.getBean(AgentRepository.class).findOwnerByVersionId(task.agentVersionId()).orElseThrow();
            disputes.acceptRuling(disputeId, userId);
        }
    }
}
