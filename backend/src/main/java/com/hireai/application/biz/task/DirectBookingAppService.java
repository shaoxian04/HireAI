package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.DirectBookingInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Direct booking (spec §4.3): client hires a SPECIFIC agent. Validates the target is ACTIVE +
 * listed and budget >= price, adopts the agent's output_spec as the binding contract
 * (Invariant #4), then submits with escrow freeze (Invariant #1) and a pinned dispatch that
 * SKIPS matching.
 */
@Validated
public interface DirectBookingAppService {

    UUID book(@NonNull DirectBookingInfo info);
}
