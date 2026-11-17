package com.hireai.support.storage;

import com.hireai.application.port.delivery.DeliveryContentStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * An in-memory fake of the private, content-addressed store ({@link DeliveryContentStore}) that DeliverySnapshots
 * and SourceSnapshots share, for unit tests that need real put/get/retire behaviour without Postgres.
 * {@code stillReferenced} stands in for "a live snapshot still holds this hash" (the Postgres adapter's
 * NOT EXISTS checks); by default nothing is referenced. {@link ContentStoreContract} holds both to the
 * same behaviour.
 */
public final class InMemoryContentStore implements DeliveryContentStore {

    private final Map<String, byte[]> bytes = new ConcurrentHashMap<>();
    private final Predicate<String> stillReferenced;

    public InMemoryContentStore() {
        this(hash -> false);
    }

    public InMemoryContentStore(Predicate<String> stillReferenced) {
        this.stillReferenced = stillReferenced;
    }

    @Override
    public void put(String sha256, byte[] content) {
        bytes.putIfAbsent(sha256, content.clone());
    }

    @Override
    public Optional<byte[]> get(String sha256) {
        return Optional.ofNullable(bytes.get(sha256)).map(byte[]::clone);
    }

    @Override
    public boolean retire(String sha256) {
        if (stillReferenced.test(sha256)) {
            return false;
        }
        return bytes.remove(sha256) != null;
    }

    /** How many contents are held (for assertions). */
    public int size() {
        return bytes.size();
    }
}
