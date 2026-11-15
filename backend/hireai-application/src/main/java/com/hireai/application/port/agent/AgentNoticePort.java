package com.hireai.application.port.agent;

/**
 * Sends a platform notice to an Agent's webhook after dispatch, under the same rules as a WorkOrder
 * (Hard Invariant #6): HTTPS only, SSRF-guarded at send time, no redirects, a bearer dispatch token,
 * and an {@code X-HireAI-Signature} over the exact body when the Agent has a signing secret.
 */
public interface AgentNoticePort {

    /**
     * Posts the rejection notice. Returns true when the Agent answered 2xx; false when the send
     * failed (unreachable, non-2xx, refused by the SSRF guard), so the caller can try again later.
     */
    boolean sendRejectionNotice(String webhookUrl, String bearerToken, String signingSecret, RejectionNotice notice);
}
