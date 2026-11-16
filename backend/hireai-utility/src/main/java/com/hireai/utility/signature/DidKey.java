package com.hireai.utility.signature;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Ed25519 public keys as {@code did:key} identifiers and Multikey values (W3C did:key, Controlled
 * Identifiers "Multikey"): base58btc multibase ({@code z…}) of the multicodec prefix {@code 0xed01}
 * followed by the raw 32-byte key. A verifier needs nothing but the identifier to get the key back,
 * which is what lets a receipt credential verify offline (docs/details/receipts.md).
 */
public final class DidKey {

    private static final String PREFIX = "did:key:";
    private static final byte[] ED25519_PUB = {(byte) 0xed, 0x01};
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private DidKey() {
    }

    /** {@code did:key:z6Mk…} for a raw Ed25519 public key. */
    public static String of(byte[] rawPublicKey) {
        return PREFIX + multikey(rawPublicKey);
    }

    /** The key's verification method id inside its own DID document: {@code did:key:z…#z…}. */
    public static String verificationMethod(byte[] rawPublicKey) {
        String multikey = multikey(rawPublicKey);
        return PREFIX + multikey + "#" + multikey;
    }

    /** The Multikey {@code publicKeyMultibase} value: {@code z} + base58btc(0xed01 ‖ key). */
    public static String multikey(byte[] rawPublicKey) {
        if (rawPublicKey == null || rawPublicKey.length != 32) {
            throw new IllegalArgumentException("An Ed25519 public key is 32 bytes");
        }
        byte[] prefixed = Arrays.copyOf(ED25519_PUB, ED25519_PUB.length + 32);
        System.arraycopy(rawPublicKey, 0, prefixed, ED25519_PUB.length, 32);
        return "z" + base58btc(prefixed);
    }

    /** The raw Ed25519 key of a {@code did:key} or of one of its verification method ids. */
    public static byte[] publicKey(String didOrVerificationMethod) {
        if (didOrVerificationMethod == null || !didOrVerificationMethod.startsWith(PREFIX + "z")) {
            throw new IllegalArgumentException("Not a base58btc did:key");
        }
        String id = didOrVerificationMethod.substring(PREFIX.length());
        int hash = id.indexOf('#');
        if (hash >= 0) {
            if (!id.substring(hash + 1).equals(id.substring(0, hash))) {
                throw new IllegalArgumentException("A did:key verification method names its own key");
            }
            id = id.substring(0, hash);
        }
        byte[] decoded = decodeBase58btc(id.substring(1));
        if (decoded.length != ED25519_PUB.length + 32 || decoded[0] != ED25519_PUB[0] || decoded[1] != ED25519_PUB[1]) {
            throw new IllegalArgumentException("Not an Ed25519 did:key");
        }
        return Arrays.copyOfRange(decoded, ED25519_PUB.length, decoded.length);
    }

    /** Bitcoin base58 (no multibase prefix); each leading zero byte becomes a leading {@code 1}. */
    public static String base58btc(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        BigInteger n = new BigInteger(1, bytes);
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(BASE);
            out.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (int i = 0; i < bytes.length && bytes[i] == 0; i++) {
            out.append('1');
        }
        return out.reverse().toString();
    }

    public static byte[] decodeBase58btc(String text) {
        BigInteger n = BigInteger.ZERO;
        int zeros = 0;
        boolean leading = true;
        for (char c : text.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("Not base58btc: " + c);
            }
            if (leading && digit == 0) {
                zeros++;
            } else {
                leading = false;
            }
            n = n.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = n.signum() == 0 ? new byte[0] : n.toByteArray();
        int skip = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0; // BigInteger's sign byte
        byte[] out = new byte[zeros + magnitude.length - skip];
        System.arraycopy(magnitude, skip, out, zeros, magnitude.length - skip);
        return out;
    }
}
