package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;

import java.util.UUID;

/**
 * Wraps the two submit use cases with three edge guards — idempotency dedup, per-key spend cap, and
 * key→task attribution — around the UNCHANGED submit/escrow/routing core. Idempotency's same-tx insert
 * protects Invariant #1 under retries: a duplicate rolls the whole submit back, undoing the freeze.
 */
public interface SubmitOrchestrationAppService {

    UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info);

    UUID submitDirect(SubmitContext ctx, DirectBookingInfo info);
}
