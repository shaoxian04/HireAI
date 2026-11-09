package com.hireai.controller.biz.agent.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Registration response: the agent's usual fields plus its AgentSigningSecret, shown this once.
 * A separate type from {@link AgentDTO} so no read endpoint can return the secret by accident.
 */
public record RegisteredAgentDTO(@JsonUnwrapped AgentDTO agent, String signingSecret) {
}
