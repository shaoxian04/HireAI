package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.service.AgentSigningSecretGenerator;

import java.security.SecureRandom;
import java.util.HexFormat;

public class AgentSigningSecretGeneratorImpl implements AgentSigningSecretGenerator {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return PREFIX + HexFormat.of().formatHex(buf);
    }
}
