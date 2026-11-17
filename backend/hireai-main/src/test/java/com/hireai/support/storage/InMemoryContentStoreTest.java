package com.hireai.support.storage;

import com.hireai.application.port.delivery.DeliveryContentStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory fake honours the storage contract, and keeps bytes a live snapshot still references. */
class InMemoryContentStoreTest extends ContentStoreContract {

    private final InMemoryContentStore store = new InMemoryContentStore();

    @Override
    protected DeliveryContentStore store() {
        return store;
    }

    @Test
    void bytesAStillLiveSnapshotReferencesAreNotRetired() {
        InMemoryContentStore referenced = new InMemoryContentStore(hash -> true);
        referenced.put("ab".repeat(32), new byte[]{1, 2, 3});

        assertThat(referenced.retire("ab".repeat(32))).isFalse();
        assertThat(referenced.get("ab".repeat(32))).isPresent();
    }
}
