package com.hireai.domain.biz.offering.agent.repository;

/**
 * Query object for paginated agent reads. Page is zero-based; size is clamped to a sane
 * range. Mirrors TaskQuery so the read paths look the same.
 */
public record AgentQuery(int page, int size) {

    public AgentQuery {
        if (page < 0) {
            page = 0;
        }
        if (size < 1 || size > 100) {
            size = 50;
        }
    }

    public static AgentQuery firstPage() {
        return new AgentQuery(0, 50);
    }
}
