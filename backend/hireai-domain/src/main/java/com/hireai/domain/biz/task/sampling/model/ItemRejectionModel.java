package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.rejection.model.RejectionModel;

import java.util.UUID;

/**
 * A reviewer's rejection of one Item on its rejection response path (spec D17′): the same round as a
 * Task's (#95's {@link RejectionModel}: revise once, concede or stand by; then the buyer decides), kept
 * per Item. Its windows count business days, as a multi-Item Task's do.
 */
public record ItemRejectionModel(UUID itemId, int itemNo, RejectionModel round) {

    public ItemRejectionModel withRound(RejectionModel next) {
        return new ItemRejectionModel(itemId, itemNo, next);
    }
}
