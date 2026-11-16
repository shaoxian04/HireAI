package com.hireai.application.port.security;

import com.hireai.utility.exception.DispatchTokenInvalidException;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims carried by a dispatch token: the task and agent version it authorises
 * and its expiry. Returned by {@link DispatchTokenService#verify(String)} once signature
 * and expiry pass. The callback app service (Plan 3) checks these against the path
 * {@code taskId} before recording a result (Hard Invariant #6).
 */
public record DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiresAt) {

    /** Refuses (401) a token issued for another task than the one the request names. */
    public DispatchTokenClaims requireTask(UUID requestTaskId) {
        if (!taskId.equals(requestTaskId)) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token task " + taskId + " does not match request task " + requestTaskId);
        }
        return this;
    }

    /** Refuses (401) a token issued to another Agent version than the one the task is assigned to now. */
    public DispatchTokenClaims requireAssignedVersion(UUID assignedVersionId) {
        if (!agentVersionId.equals(assignedVersionId)) {
            throw new DispatchTokenInvalidException("Dispatch token agent version " + agentVersionId
                    + " does not match task " + taskId + " assignment " + assignedVersionId);
        }
        return this;
    }
}
