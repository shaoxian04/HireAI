package com.hireai.domain.biz.offering.embedding.model;

import com.hireai.utility.hash.Sha256;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The text that describes an Agent to the matcher: its name, tagline and description, trimmed,
 * blanks dropped, one per line. Its SHA-256 {@link #hash()} is stored next to the vector, so a
 * vector whose text has since changed is recognisably stale.
 */
public record AgentProfileText(String text) {

    public AgentProfileText {
        Objects.requireNonNull(text, "text");
    }

    public static AgentProfileText of(String name, String tagline, String description) {
        return new AgentProfileText(Stream.of(name, tagline, description)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n")));
    }

    public String hash() {
        return Sha256.hex(text);
    }
}
