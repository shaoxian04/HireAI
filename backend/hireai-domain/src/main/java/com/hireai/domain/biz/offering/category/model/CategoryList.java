package com.hireai.domain.biz.offering.category.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The platform-owned category list (ADR 0006): the one vocabulary Builders tag Agents with and
 * Clients route, book and preview by. Reference data, changed only by migration. Every entry point
 * that accepts a category asks this list, so an unknown slug is a 400 that names the valid ones.
 */
public final class CategoryList {

    private final List<Category> categories;

    public CategoryList(List<Category> categories) {
        this.categories = List.copyOf(categories);
    }

    public List<Category> categories() {
        return categories;
    }

    /**
     * @return the normalised (trimmed, lowercased) slug
     * @throws DomainException VALIDATION_ERROR naming every valid slug when {@code raw} is not one
     */
    public String requireKnown(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (categories.stream().noneMatch(c -> c.slug().equals(slug))) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unknown category '" + slug + "'. Valid categories: " + slugs());
        }
        return slug;
    }

    /** {@link #requireKnown} for each tag, in order. */
    public List<String> requireAllKnown(Collection<String> raw) {
        return raw.stream().map(this::requireKnown).toList();
    }

    private String slugs() {
        return categories.stream().map(Category::slug).collect(Collectors.joining(", "));
    }
}
