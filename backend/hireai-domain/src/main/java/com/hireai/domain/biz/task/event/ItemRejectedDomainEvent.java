package com.hireai.domain.biz.task.event;

import java.util.UUID;

/**
 * Published when a buyer's reviewer rejects one Item (spec S8, D17′), opening its rejection round.
 * After the judgement commits, a listener sends the Agent the Item's signed rejection notice; the
 * rejection-window sweeper retries a notice that did not get through.
 */
public record ItemRejectedDomainEvent(UUID taskId, UUID itemId) {
}
