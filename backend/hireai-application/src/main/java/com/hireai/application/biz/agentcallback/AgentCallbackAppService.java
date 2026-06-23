package com.hireai.application.biz.agentcallback;

import com.hireai.domain.biz.task.info.AgentResultInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the agent result-callback use case. This is the one non-JWT entry point:
 * the caller is the Agent, authenticated by the short-lived dispatch token (Hard Invariant #6).
 * The bearer token is verified before any state changes; an invalid/expired/mismatched token
 * throws a {@code DispatchTokenInvalidException} (mapped to HTTP 401 at the controller).
 */
@Validated
public interface AgentCallbackAppService {

    void recordResult(@NonNull UUID taskId, @NonNull String bearerToken, @NonNull AgentResultInfo result);
}
