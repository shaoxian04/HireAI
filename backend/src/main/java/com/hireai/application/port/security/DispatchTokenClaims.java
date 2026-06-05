package com.hireai.application.port.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims carried by a dispatch token: the task and agent version it authorises
 * and its expiry. Returned by {@link DispatchTokenService#verify(String)} once signature
 * and expiry pass. The callback app service (Plan 3) checks these against the path
 * {@code taskId} before recording a result (Hard Invariant #6).
 */
public record DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiresAt) {
}
