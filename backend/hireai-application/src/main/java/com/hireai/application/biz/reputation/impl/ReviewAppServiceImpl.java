package com.hireai.application.biz.reputation.impl;

import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.reputation.ReviewAppService;
import com.hireai.domain.biz.offering.agent.info.AgentReputationTarget;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.model.ReviewModel;
import com.hireai.domain.biz.reputation.repository.ReviewRepository;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Thin orchestration: check the four gates, persist the review, hand the stars to reputation.
 * The scoring itself lives in the domain.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReviewAppServiceImpl implements ReviewAppService {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final ReviewRepository reviewRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final ReputationWriteAppService reputationWriteAppService;

    @Override
    public ReviewModel review(UUID taskId, UUID clientId, int rating, String reviewText) {
        TaskModel task = loadOwned(taskId, clientId);
        requireAccepted(task);
        requireHumanChannel(task);

        AgentReputationTarget target = agentRepository
                .findReputationTargetByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent for version " + task.agentVersionId()));

        // A builder cannot review their own agent. ReputationWriteAppService would decline to
        // score it anyway, but the review row itself must not exist either — otherwise the
        // storefront's star average is farmable even though the routing score is not.
        if (target.ownerId().equals(clientId)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "You cannot review your own agent");
        }
        if (reviewRepository.existsByTaskId(taskId)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "This task has already been reviewed");
        }

        ReviewModel review = reviewRepository.save(
                ReviewModel.forTask(taskId, clientId, target.agentId(), rating, reviewText));

        reputationWriteAppService.recordRating(taskId, task.agentVersionId(), clientId, rating);

        log.info("Review: client {} rated task {} {} star(s) for agent {}",
                clientId, taskId, rating, target.agentId());
        return review;
    }

    /** Invariant #5: a foreign task is indistinguishable from a missing one. */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    /**
     * Accepted-only. Note this is deliberately keyed off BOTH status and resolution: a changed-mind
     * rejection settles like an acceptance but records its resolution as REJECTED, and a task the
     * client disputed reaches RESOLVED by a route that already had its say.
     */
    private static void requireAccepted(TaskModel task) {
        if (task.status() != TaskStatus.RESOLVED || task.resolution() != TaskResolution.ACCEPTED) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Only a task you accepted can be reviewed");
        }
    }

    /**
     * A programmatic task was settled by machine validation with no human in the loop, so there is
     * nobody whose satisfaction a star could represent. Leaving it unrated is the honest outcome —
     * it shows up as missing evidence rather than as assumed excellence.
     */
    private void requireHumanChannel(TaskModel task) {
        if (apiKeyTaskRepository.findApiKeyIdByTask(task.id()).isPresent()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "API-submitted tasks are not reviewable");
        }
    }
}
