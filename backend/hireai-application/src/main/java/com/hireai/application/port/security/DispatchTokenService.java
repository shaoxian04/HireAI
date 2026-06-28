package com.hireai.application.port.security;

import java.time.Duration;
import java.util.UUID;

/**
 * Application port for issuing and verifying short-lived signed dispatch tokens
 * (Hard Invariant #6). The dispatch consumer (Plan 2) issues a token bound to a task and
 * agent version with a bounded TTL; the agent returns it on the result callback, where the
 * callback app service (Plan 3) verifies it. Plan 2 provides the HMAC-backed implementation
 * in {@code infrastructure/security}.
 */
public interface DispatchTokenService {

    String issue(UUID taskId, UUID agentVersionId, Duration ttl);

    DispatchTokenClaims verify(String token);
}
