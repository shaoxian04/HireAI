package com.hireai.controller.biz.acceptance.dto;

import java.time.Instant;

/**
 * The Client is told (spec D36): the Contract for {@code template} was made under
 * {@code usedVersion}, the previous version, because no Agent's Offer covers {@code currentVersion}
 * yet. {@code usedVersion} takes new Contracts until {@code transitionEndsAt}.
 */
public record VersionFallbackDTO(String template, int currentVersion, int usedVersion,
                                 Instant transitionEndsAt, String summary) {
}
