package com.hireai.utility.hash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Deterministic HMAC-SHA256 → lowercase hex. Shared by webhook signing (mirrors {@link Sha256}). */
public final class HmacSha256 {
    private static final String ALG = "HmacSHA256";
    private HmacSha256() {}

    public static String hexOf(String secret, String message) {
        return hexOf(secret, message.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC over raw bytes, so a body is signed exactly as received (no charset round-trip). */
    public static String hexOf(String secret, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG));
            for (byte[] part : parts) {
                mac.update(part);
            }
            byte[] sig = mac.doFinal();
            StringBuilder sb = new StringBuilder(sig.length * 2);
            for (byte b : sig) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // never on a standard JRE
        }
    }
}
