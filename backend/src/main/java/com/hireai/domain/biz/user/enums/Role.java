package com.hireai.domain.biz.user.enums;

/**
 * Platform roles. Mirrors the {@code users.role} CHECK constraint (CLIENT / BUILDER / ADMIN).
 * Carried in the JWT and surfaced as a {@code ROLE_<name>} Spring authority. Per-endpoint role
 * gating is a later slice; this slice only authenticates.
 */
public enum Role {
    CLIENT,
    BUILDER,
    ADMIN
}
