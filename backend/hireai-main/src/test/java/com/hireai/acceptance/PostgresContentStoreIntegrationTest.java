package com.hireai.acceptance;

import com.hireai.application.port.delivery.DeliveryContentStore;
import com.hireai.support.harness.WholeAppIntegrationTest;
import com.hireai.support.storage.ContentStoreContract;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Postgres adapter of the private storage port honours the same contract as the in-memory fake
 * ({@link ContentStoreContract}). Each call runs in its own transaction, as in the app (the adapter takes a
 * transaction-scoped advisory lock).
 */
class PostgresContentStoreIntegrationTest extends WholeAppIntegrationTest {

    @Autowired DeliveryContentStore postgres;
    @Autowired TransactionTemplate transactions;

    @Nested
    class Contract extends ContentStoreContract {
        @Override
        protected DeliveryContentStore store() {
            return new DeliveryContentStore() {
                @Override
                public void put(String sha256, byte[] content) {
                    transactions.executeWithoutResult(s -> postgres.put(sha256, content));
                }

                @Override
                public java.util.Optional<byte[]> get(String sha256) {
                    return transactions.execute(s -> postgres.get(sha256));
                }

                @Override
                public boolean retire(String sha256) {
                    return Boolean.TRUE.equals(transactions.execute(s -> postgres.retire(sha256)));
                }
            };
        }
    }
}
