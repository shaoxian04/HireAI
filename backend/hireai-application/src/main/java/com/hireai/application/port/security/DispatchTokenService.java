package com.hireai.application.port.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Application port for issuing and verifying signed dispatch tokens (Hard Invariant #6). The
 * dispatch consumer issues a token bound to a task and agent version; the Agent returns it on the
 * result callback, where the callback app service verifies it. The HMAC-backed implementation
 * lives in {@code infrastructure/security}.
 *
 * <p>A token lives exactly until the task's execution deadline plus {@link #CALLBACK_MARGIN}, so
 * an Agent working within its declared limit never gets a 401.
 */
public interface DispatchTokenService {

    /** How long after the task's execution deadline its callback token stays valid. */
    Duration CALLBACK_MARGIN = Duration.ofMinutes(5);

    /** Issues a token that expires at {@code executionDeadline} + {@link #CALLBACK_MARGIN}. */
    String issue(UUID taskId, UUID agentVersionId, Instant executionDeadline);

    DispatchTokenClaims verify(String token);
}
