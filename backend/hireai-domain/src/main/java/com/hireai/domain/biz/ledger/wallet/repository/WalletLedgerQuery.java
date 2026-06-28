package com.hireai.domain.biz.ledger.wallet.repository;

/**
 * Read/pagination query object for the wallet ledger. Kept framework-free in the
 * domain layer; the repository implementation maps it to the persistence query.
 */
public record WalletLedgerQuery(int page, int size) {

    public WalletLedgerQuery {
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;
    }

    public static WalletLedgerQuery firstPage() {
        return new WalletLedgerQuery(0, 50);
    }

    public int offset() {
        return page * size;
    }
}
