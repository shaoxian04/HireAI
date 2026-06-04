package com.hireai.domain.biz.task.repository;

/**
 * Query object for paginated task reads. Page is zero-based; size is clamped to a
 * sane range. Mirrors WalletLedgerQuery so the read paths look the same.
 */
public record TaskQuery(int page, int size) {

    public TaskQuery {
        if (page < 0) {
            page = 0;
        }
        if (size < 1 || size > 100) {
            size = 50;
        }
    }

    public static TaskQuery firstPage() {
        return new TaskQuery(0, 50);
    }
}
