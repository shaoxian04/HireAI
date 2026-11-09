package com.hireai.domain.biz.offering.category.model;

/** One bucket of the platform-owned category list: a stable slug and a display label. */
public record Category(String slug, String label) {
}
