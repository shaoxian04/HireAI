package com.hireai.domain.biz.user.enums;

/**
 * Platform roles. Mirrors the {@code user_roles.role} CHECK constraint (CLIENT / BUILDER / ADMIN).
 * A user may hold more than one (dual-capability: every user is a CLIENT, BUILDER is opt-in). Each
 * role is carried in the JWT {@code roles} claim and surfaced as a {@code ROLE_<name>} Spring authority.
 */
public enum Role {
    CLIENT,
    BUILDER,
    ADMIN
}
