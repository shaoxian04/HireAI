package com.hireai.domain.biz.offering.embedding.model;

import com.hireai.utility.hash.Sha256;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The text that describes a task to the matcher: its title and description, trimmed, blanks dropped,
 * one per line (the same rule as {@link AgentProfileText}). Its SHA-256 {@link #hash()} keys the
 * {@code text_embeddings} cache, so a match preview and the booking or submit that follows it with the
 * same text share one vector.
 */
public record TaskText(String text) {

    public TaskText {
        Objects.requireNonNull(text, "text");
    }

    public static TaskText of(String title, String description) {
        return new TaskText(Stream.of(title, description)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n")));
    }

    public boolean isBlank() {
        return text.isBlank();
    }

    public String hash() {
        return Sha256.hex(text);
    }
}
