package com.hireai.support.agent;

/**
 * What happened when the fake Agent sent a scripted callback: the request it sent, the
 * platform's HTTP status and response body, and whether it went out before the WorkOrder reply.
 * {@code status} is -1 when the request itself failed (see {@code error}).
 */
public record CallbackOutcome(
        CallbackRequest request,
        int status,
        String responseBody,
        boolean sentBeforeReply,
        String error
) {
}
