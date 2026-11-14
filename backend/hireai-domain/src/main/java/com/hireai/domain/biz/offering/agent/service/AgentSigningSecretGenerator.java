package com.hireai.domain.biz.offering.agent.service;

/**
 * Generates an unguessable AgentSigningSecret: {@code agsec_} + 32 random bytes as hex. The prefix
 * tells it apart from a client webhook secret ({@code whsec_}). Framework-free; wired in
 * DomainServiceConfig.
 */
public interface AgentSigningSecretGenerator {

    String PREFIX = "agsec_";

    String generate();
}
