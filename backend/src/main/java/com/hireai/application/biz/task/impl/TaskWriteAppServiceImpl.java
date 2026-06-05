package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskWriteAppServiceImpl implements TaskWriteAppService {

    private final TaskRepository taskRepository;
    private final TaskSubmitDomainService taskSubmitDomainService;
    private final WalletWriteAppService walletWriteAppService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public UUID submit(TaskSubmitInfo taskSubmitInfo) {
        String correlationId = UUID.randomUUID().toString();
        TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo);
        UUID taskId = taskRepository.save(task).id();
        walletWriteAppService.freeze(taskSubmitInfo.clientId(), taskSubmitInfo.budget(), taskId, correlationId);
        eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), task.createdAt()));
        log.info("Task {} submitted by client {}; budget {} frozen in escrow",
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget());
        return taskId;
    }
}
