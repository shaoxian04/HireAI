package com.hireai.application.biz.task.callback;

import com.hireai.domain.biz.task.info.AgentResultInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the agent result-callback use case. This is the one non-JWT entry point: the
 * caller is the Agent, authenticated by the short-lived dispatch token AND an
 * {@code X-HireAI-Signature} over the raw body made with the Agent's signing secret (Hard
 * Invariant #6). Any authentication failure throws a {@code DispatchTokenInvalidException}
 * (mapped to HTTP 401 at the controller).
 *
 * <p>The HTTP entry point must call {@link #authenticate} on the raw body BEFORE parsing it, and
 * only then {@link #recordResult}.
 */
@Validated
public interface AgentCallbackAppService {

    /**
     * Verifies the dispatch token for {@code taskId}, then the signature over {@code rawBody} with
     * the current signing secret of the token's Agent version, timestamp within ±5 minutes. An
     * Agent with no secret can never pass (it must rotate first). Changes no state.
     */
    void authenticate(@NonNull UUID taskId, @NonNull String bearerToken, @Nullable String signatureHeader,
                      byte @NonNull [] rawBody);

    /**
     * Records an already-authenticated result. Re-checks the token, and under the task row lock
     * that the token's Agent version is the one assigned to the task; first result wins.
     */
    void recordResult(@NonNull UUID taskId, @NonNull String bearerToken, @NonNull AgentResultInfo result);
}
