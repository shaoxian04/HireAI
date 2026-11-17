package com.hireai.support.storage;

import com.hireai.application.port.delivery.DeliveryContentStore;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link DeliveryContentStore} must do (the private storage port, S4/D24/D32), run against the
 * in-memory fake and the Postgres adapter alike, so a test using the fake tests the real behaviour.
 */
public abstract class ContentStoreContract {

    /** A store in which nothing references any hash, so retire deletes. */
    protected abstract DeliveryContentStore store();

    @Test
    void storedBytesAreReadBackByTheirHashAndStoringAgainIsANoOp() {
        byte[] content = ("document " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String hash = Sha256.hex(new String(content, StandardCharsets.UTF_8));

        store().put(hash, content);
        store().put(hash, content);

        assertThat(store().get(hash)).hasValueSatisfying(b -> assertThat(b).isEqualTo(content));
    }

    @Test
    void anUnknownHashHasNoBytes() {
        assertThat(store().get(Sha256.hex("never stored " + UUID.randomUUID()))).isEmpty();
    }

    @Test
    void retiringDeletesTheBytesOnceAndKeepsNothingBehind() {
        byte[] content = ("retire me " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String hash = Sha256.hex(new String(content, StandardCharsets.UTF_8));
        store().put(hash, content);

        assertThat(store().retire(hash)).isTrue();
        assertThat(store().get(hash)).isEmpty();
        assertThat(store().retire(hash)).isFalse();
    }
}
