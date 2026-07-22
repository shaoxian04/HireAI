package com.hireai.domain.biz.webhook;

import com.hireai.utility.hash.HmacSha256;

/** Stripe-style webhook signature: HMAC-SHA256 over "{ts}.{body}", header "t=<ts>,v1=<hex>". */
public final class WebhookSignature {
    private WebhookSignature() {}

    public static String header(String secret, long tsEpochSeconds, String body) {
        String v1 = HmacSha256.hexOf(secret, tsEpochSeconds + "." + body);
        return "t=" + tsEpochSeconds + ",v1=" + v1;
    }
}
