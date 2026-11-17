package com.hireai.application.port.receipt;

import java.util.List;

/**
 * The platform's receipt-signing key (spec D40): Ed25519 over the RFC 8785 bytes of a receipt.
 * The private key never leaves the adapter; {@link #publishedKeys()} is what verifiers fetch,
 * the active key first, then retired keys still needed to verify older receipts.
 */
public interface ReceiptSigner {

    /** The id of the key {@link #sign} uses; written into every receipt it signs. */
    String keyId();

    /** The Ed25519 signature of {@code message}, base64url without padding. */
    String sign(byte[] message);

    /** Every public key a verifier may need: the active key first, then retired ones. */
    List<PublicKey> publishedKeys();

    /** One published Ed25519 public key: its id, the raw 32-byte key, and whether it still signs. */
    record PublicKey(String keyId, byte[] raw, boolean active) {
        public PublicKey {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }
}
