package com.hireai.domain.biz.offering.agent.service;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates an unguessable AgentSigningSecret: {@code agsec_} + 32 random bytes as hex. The prefix
 * tells it apart from a client webhook secret ({@code whsec_}). Framework-free; wired in
 * DomainServiceConfig.
 */
public class AgentSigningSecretGenerator {

    public static final String PREFIX = "agsec_";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return PREFIX + HexFormat.of().formatHex(buf);
    }
}
