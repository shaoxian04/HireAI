package com.hireai.application.biz.task.reliability;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the two reliability sweeps behind {@code TaskReliability} (spec §6):
 * re-matching tasks stuck AWAITING_CAPACITY (attempt-bounded, refunding on exhaustion) and
 * timing out tasks that exceeded their execution deadline (TIMED_OUT + full refund). The id
 * reads and {@link #rematchOne} / {@link #timeoutOne} form the read-then-drive loop each
 * scheduled sweeper runs one id at a time so a single task's failure never blocks the sweep.
 */
public interface TaskReliabilityAppService {

    /** Ids currently AWAITING_CAPACITY, for the re-match sweeper to drive one at a time. */
    List<UUID> awaitingCapacityTaskIds();

    /**
     * Re-match one AWAITING_CAPACITY task: increments its attempt counter, retries routing
     * (pinned direct-booking version if one exists, otherwise full matching), and cancels with
     * a full refund if the attempt bound is reached and routing still left it unmatched.
     * NOT transactional — drives {@code RoutingAppService}, which publishes to RabbitMQ under
     * its own commit-before-publish ordering. Status-guarded no-op if the task already left
     * AWAITING_CAPACITY since the sweep listed it.
     */
    void rematchOne(UUID taskId);

    /** Ids past their execution deadline, for the timeout sweeper to drive one at a time. */
    List<UUID> executionExpiredTaskIds();

    /**
     * Times out one task past its execution deadline: QUEUED/EXECUTING -> TIMED_OUT with a full
     * escrow refund. Transactional. Status-guarded no-op if the task already left QUEUED/EXECUTING
     * (e.g. a result arrived) since the sweep listed it.
     */
    void timeoutOne(UUID taskId);
}
