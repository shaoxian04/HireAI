package com.hireai.infrastructure.security.impl;

import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.utility.signature.Ed25519;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Signs receipts with the platform Ed25519 key from {@code hireai.receipts.signing-key} (base64
 * PKCS#8 DER, from the environment; never committed). Without one it generates a key for this
 * process and warns: receipts it signs stop verifying once the process ends, which is acceptable
 * in development and tests only. Retired public keys ({@code hireai.receipts.retired-public-keys},
 * comma-separated base64 SubjectPublicKeyInfo, each optionally prefixed {@code keyId:}) stay
 * published so receipts signed before a rotation still verify. See docs/receipts.md.
 */
@Slf4j
@Service
public class Ed25519ReceiptSigner implements ReceiptSigner {

    private final PrivateKey privateKey;
    private final String keyId;
    private final List<PublicKey> publishedKeys;

    public Ed25519ReceiptSigner(@Value("${hireai.receipts.signing-key:}") String signingKey,
                                @Value("${hireai.receipts.key-id:}") String keyIdOverride,
                                @Value("${hireai.receipts.retired-public-keys:}") String retiredPublicKeys) {
        KeyPair pair = isBlank(signingKey) ? ephemeral() : fromConfig(signingKey.trim());
        this.privateKey = pair.getPrivate();
        byte[] raw = Ed25519.raw(pair.getPublic());
        this.keyId = isBlank(keyIdOverride) ? Ed25519.keyId(raw) : keyIdOverride.trim();

        List<PublicKey> keys = new ArrayList<>();
        keys.add(new PublicKey(keyId, raw, true));
        keys.addAll(retired(retiredPublicKeys));
        this.publishedKeys = List.copyOf(keys);
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String sign(byte[] message) {
        return Ed25519.sign(privateKey, message);
    }

    @Override
    public List<PublicKey> publishedKeys() {
        return publishedKeys;
    }

    private static KeyPair ephemeral() {
        log.warn("hireai.receipts.signing-key is not set: signing receipts with a key generated for this process. "
                + "Receipts it signs cannot be verified after a restart; set RECEIPT_SIGNING_KEY in real deploys.");
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /**
     * Loads the configured private key and re-derives its public key. The JDK has no public API for
     * that, so the key generator is fed the private key's own seed: Ed25519 key generation is a pure
     * function of the 32-byte seed (RFC 8032 §5.1.5).
     */
    private static KeyPair fromConfig(String base64) {
        try {
            PrivateKey key = Ed25519.privateKeyFromPkcs8(Base64.getDecoder().decode(base64));
            byte[] seed = ((EdECPrivateKey) key).getBytes()
                    .orElseThrow(() -> new IllegalArgumentException("The private key has no seed"));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed));
            KeyPair derived = generator.generateKeyPair();
            byte[] probe = "receipt-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!Ed25519.verify(Ed25519.raw(derived.getPublic()), probe, Ed25519.sign(key, probe))) {
                throw new IllegalArgumentException("Could not derive the public key");
            }
            return new KeyPair(derived.getPublic(), key);
        } catch (IllegalArgumentException | ClassCastException | GeneralSecurityException e) {
            throw new IllegalStateException("hireai.receipts.signing-key must be a base64 Ed25519 PKCS#8 private key", e);
        }
    }

    private static List<PublicKey> retired(String config) {
        List<PublicKey> keys = new ArrayList<>();
        if (isBlank(config)) {
            return keys;
        }
        for (String entry : config.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':'); // base64 never contains ':'
            boolean named = colon > 0;
            String spki = named ? trimmed.substring(colon + 1).trim() : trimmed;
            byte[] raw;
            try {
                raw = Ed25519.raw(Ed25519.publicKeyFromSpki(Base64.getDecoder().decode(spki)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("hireai.receipts.retired-public-keys has an entry that is not "
                        + "a base64 Ed25519 public key", e);
            }
            keys.add(new PublicKey(named ? trimmed.substring(0, colon).trim() : Ed25519.keyId(raw), raw, false));
        }
        return keys;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Hands the key generator one fixed seed. */
    private static final class SeedRandom extends SecureRandom {
        private final byte[] seed;

        SeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
        }
    }
}
