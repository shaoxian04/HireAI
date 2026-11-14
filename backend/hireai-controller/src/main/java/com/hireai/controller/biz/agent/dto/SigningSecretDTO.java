package com.hireai.controller.biz.agent.dto;

import java.util.UUID;

/** Rotation response: the agent's new AgentSigningSecret, shown this once. */
public record SigningSecretDTO(UUID agentId, String signingSecret) {
}
