package com.hireai.utility.signature;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expected base58btc values come from the draft-msporny-base58 examples; the did:key vector is the
 * W3C did:key method's Ed25519 example (its multicodec-prefixed key, 0xed01 + 32 bytes).
 */
class DidKeyTest {

    @Test
    void base58btcMatchesTheDraftExamples() {
        assertThat(DidKey.base58btc("Hello World!".getBytes(StandardCharsets.US_ASCII))).isEqualTo("2NEpo7TZRRrLZSi2U");
        assertThat(DidKey.base58btc(HexFormat.of().parseHex("0000287fb4cd"))).isEqualTo("11233QC4");
        assertThat(DidKey.decodeBase58btc("11233QC4")).isEqualTo(HexFormat.of().parseHex("0000287fb4cd"));
        assertThat(DidKey.decodeBase58btc("2NEpo7TZRRrLZSi2U"))
                .isEqualTo("Hello World!".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void anEd25519KeyBecomesADidKeyAndBack() throws Exception {
        byte[] raw = Ed25519.raw(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());

        String did = DidKey.of(raw);

        assertThat(did).startsWith("did:key:z6Mk"); // every Ed25519 did:key starts so (0xed01 prefix)
        assertThat(DidKey.publicKey(did)).isEqualTo(raw);
        assertThat(DidKey.verificationMethod(raw)).isEqualTo(did + "#" + did.substring("did:key:".length()));
        assertThat(DidKey.publicKey(DidKey.verificationMethod(raw))).isEqualTo(raw);
    }

    @Test
    void decodesTheDidKeySpecExample() {
        String did = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

        byte[] raw = DidKey.publicKey(did);

        assertThat(raw).hasSize(32);
        assertThat(DidKey.of(raw)).isEqualTo(did);
    }

    @Test
    void refusesWhatIsNotAnEd25519DidKey() {
        assertThatThrownBy(() -> DidKey.publicKey("did:web:example.org")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DidKey.publicKey("did:key:zQ3sh")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DidKey.decodeBase58btc("0OIl")).isInstanceOf(IllegalArgumentException.class);
    }
}
