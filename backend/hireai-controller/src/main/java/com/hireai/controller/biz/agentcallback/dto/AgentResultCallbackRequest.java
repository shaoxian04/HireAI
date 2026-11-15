package com.hireai.controller.biz.agentcallback.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Inbound HTTP DTO for an agent result callback. {@code taskId} is the task the result answers;
 * it is covered by the Agent's signature and must equal the callback path's task, so a signed
 * result can't be replayed onto another task. {@code agentStatus} is COMPLETED or FAILED;
 * {@code resultPayloadJson} is the (string-encoded) result body; {@code resultUrl} and
 * {@code message} are optional.
 *
 * <p>For a multi-Item Task (the WorkOrder carried {@code items}), {@code items} holds one result per
 * Item, keyed by the WorkOrder's {@code itemNo}, and {@code resultPayloadJson} may be left out. An
 * Item left out of the callback counts as FAILED.
 *
 * <p>{@code anomalies} (optional, spec D7) are what the Agent reports about the brief or source:
 * one naming a content check the Template allows sends a failure of that check to the Client's
 * review instead of a rework, and is paid if the Client confirms it by accepting. {@code check}
 * optionally names the Check Library entry the anomaly explains.
 */
public record AgentResultCallbackRequest(
        @NotNull UUID taskId,
        @NotBlank String agentStatus,
        String resultPayloadJson,
        @Size(max = 2000) String resultUrl,
        @Size(max = 2000) String message,
        @Size(max = 1000) List<@Valid @NotNull ItemResult> items,
        @Size(max = 20) List<@Valid @NotNull Anomaly> anomalies
) {

    public AgentResultCallbackRequest(UUID taskId, String agentStatus, String resultPayloadJson,
                                      String resultUrl, String message) {
        this(taskId, agentStatus, resultPayloadJson, resultUrl, message, null, null);
    }

    public AgentResultCallbackRequest(UUID taskId, String agentStatus, String resultPayloadJson,
                                      String resultUrl, String message, List<ItemResult> items) {
        this(taskId, agentStatus, resultPayloadJson, resultUrl, message, items, null);
    }

    public boolean hasItems() {
        return items != null && !items.isEmpty();
    }

    /** One Item's result. */
    public record ItemResult(
            @NotNull @Min(1) Integer itemNo,
            @NotBlank String agentStatus,
            String resultPayloadJson,
            @Size(max = 2000) String resultUrl
    ) {
    }

    /** One self-reported anomaly. */
    public record Anomaly(@Size(max = 64) String check, @NotBlank @Size(max = 1000) String description) {
    }
}
