package com.hireai.controller.biz.catalogue.dto;

/**
 * The windows a booking agrees to, in seconds: the Client's review of a delivered result
 * ({@code reviewSeconds}; silence accepts it), and after a rejection the Agent's response
 * ({@code agentResponseSeconds}) and the Client's decision ({@code clientDecisionSeconds}).
 */
public record ReviewWindowsDTO(long reviewSeconds, long agentResponseSeconds, long clientDecisionSeconds) {
}
