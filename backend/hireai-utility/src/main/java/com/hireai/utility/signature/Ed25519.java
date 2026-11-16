package com.hireai.utility.signature;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 (RFC 8032) helpers on the JDK provider: key encoding, key ids, signing and verification.
 * Signatures are base64url without padding, as receipts carry them (spec D40).
 */
public final class Ed25519 {

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410); the raw 32-byte key follows it. */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int RAW_LENGTH = 32;

    private Ed25519() {
    }

    public static PrivateKey privateKeyFromPkcs8(byte[] der) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 PKCS#8 private key", e);
        }
    }

    public static PublicKey publicKeyFromRaw(byte[] raw) {
        if (raw == null || raw.length != RAW_LENGTH) {
            throw new IllegalArgumentException("An Ed25519 public key is 32 bytes");
        }
        byte[] spki = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + RAW_LENGTH);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, RAW_LENGTH);
        return publicKeyFromSpki(spki);
    }

    public static PublicKey publicKeyFromSpki(byte[] der) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 SubjectPublicKeyInfo", e);
        }
    }

    /** The raw 32-byte key inside an Ed25519 public key's X.509 encoding. */
    public static byte[] raw(PublicKey key) {
        byte[] spki = key.getEncoded();
        if (spki.length != SPKI_PREFIX.length + RAW_LENGTH
                || !Arrays.equals(spki, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
            throw new IllegalArgumentException("Not an Ed25519 public key");
        }
        return Arrays.copyOfRange(spki, SPKI_PREFIX.length, spki.length);
    }

    /** A key id derived from the key itself: {@code ed25519-} + the first 16 hex digits of SHA-256(raw key). */
    public static String keyId(byte[] rawPublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey);
            return "ed25519-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sign(PrivateKey key, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(message);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** True only when {@code signature} (base64url) is a valid Ed25519 signature of {@code message}. */
    public static boolean verify(byte[] rawPublicKey, byte[] message, String signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKeyFromRaw(rawPublicKey));
            verifier.update(message);
            return verifier.verify(Base64.getUrlDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
