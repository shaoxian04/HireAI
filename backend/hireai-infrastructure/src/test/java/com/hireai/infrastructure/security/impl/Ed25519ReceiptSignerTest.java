package com.hireai.infrastructure.security.impl;

import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.utility.signature.Ed25519;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ed25519ReceiptSignerTest {

    private static final byte[] MESSAGE = "{\"format\":\"hireai.receipt.v1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsWithTheConfiguredKeyAndPublishesItsPublicHalf() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); // test-only key
        String pkcs8 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());

        Ed25519ReceiptSigner signer = new Ed25519ReceiptSigner(pkcs8, "", "");
        String signature = signer.sign(MESSAGE);

        ReceiptSigner.PublicKey published = signer.publishedKeys().get(0);
        assertThat(published.active()).isTrue();
        assertThat(published.raw()).isEqualTo(Ed25519.raw(pair.getPublic()));
        assertThat(published.keyId()).isEqualTo(signer.keyId()).startsWith("ed25519-");
        assertThat(Ed25519.verify(published.raw(), MESSAGE, signature)).isTrue();
    }

    @Test
    void aTamperedMessageOrSignatureDoesNotVerify() {
        Ed25519ReceiptSigner signer = new Ed25519ReceiptSigner("", "", "");
        String signature = signer.sign(MESSAGE);
        byte[] key = signer.publishedKeys().get(0).raw();

        byte[] tampered = MESSAGE.clone();
        tampered[3] ^= 1;
        assertThat(Ed25519.verify(key, tampered, signature)).isFalse();
        String otherSignature = new Ed25519ReceiptSigner("", "", "").sign(MESSAGE);
        assertThat(Ed25519.verify(key, MESSAGE, otherSignature)).isFalse();
    }

    @Test
    void withoutAConfiguredKeyItSignsWithAKeyOfItsOwn() {
        Ed25519ReceiptSigner one = new Ed25519ReceiptSigner("", "", "");
        Ed25519ReceiptSigner two = new Ed25519ReceiptSigner(null, null, null);

        assertThat(one.keyId()).isNotEqualTo(two.keyId());
        assertThat(Ed25519.verify(one.publishedKeys().get(0).raw(), MESSAGE, one.sign(MESSAGE))).isTrue();
    }

    @Test
    void publishesRetiredKeysSoOlderReceiptsStillVerify() throws Exception {
        KeyPair retired = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair named = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String spki = Base64.getEncoder().encodeToString(retired.getPublic().getEncoded());
        String namedSpki = Base64.getEncoder().encodeToString(named.getPublic().getEncoded());

        Ed25519ReceiptSigner signer = new Ed25519ReceiptSigner("", "platform-2026", spki + ", old-2025:" + namedSpki);

        assertThat(signer.keyId()).isEqualTo("platform-2026");
        assertThat(signer.publishedKeys()).extracting(ReceiptSigner.PublicKey::keyId)
                .containsExactly("platform-2026", Ed25519.keyId(Ed25519.raw(retired.getPublic())), "old-2025");
        assertThat(signer.publishedKeys()).extracting(ReceiptSigner.PublicKey::active)
                .containsExactly(true, false, false);
    }

    @Test
    void refusesAKeyThatIsNotEd25519() {
        assertThatThrownBy(() -> new Ed25519ReceiptSigner("bm90LWEta2V5", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hireai.receipts.signing-key");
    }
}
