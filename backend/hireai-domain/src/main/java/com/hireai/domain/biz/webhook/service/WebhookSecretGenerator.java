package com.hireai.domain.biz.webhook.service;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Generates an unguessable webhook signing secret. Framework-free; wired in DomainServiceConfig. */
public class WebhookSecretGenerator {
    private final SecureRandom random = new SecureRandom();
    public String generate() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return "whsec_" + HexFormat.of().formatHex(buf);
    }
}
