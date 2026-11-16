package com.hireai.domain.biz.acceptance.template.info;

import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.time.Instant;

/**
 * The Client is told (spec D36): a new Contract was made under {@code used}, the previous version,
 * instead of {@code current}, because no Offer covered the current one (or its values only fit the
 * previous one). {@code used} takes new Contracts until {@code transitionEndsAt}.
 */
public record VersionFallback(TemplateRef current, TemplateRef used, Instant transitionEndsAt) {

    /** One sentence for people (web notice, failure detail, logs). */
    public String summary() {
        return "No agent accepts " + current + " yet, so this Contract uses the previous version, " + used
                + ", which takes new Contracts until " + transitionEndsAt + ".";
    }
}
