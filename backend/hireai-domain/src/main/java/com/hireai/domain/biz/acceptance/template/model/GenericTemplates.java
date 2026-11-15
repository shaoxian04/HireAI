package com.hireai.domain.biz.acceptance.template.model;

/**
 * The generic Templates every existing output spec maps onto (spec D39). Their definitions are
 * seeded by Flyway ({@code contract_templates}); these constants only name them.
 */
public final class GenericTemplates {

    /** Plain text: the deliverable is non-blank text. */
    public static final TemplateRef TEXT_BASIC_V1 = new TemplateRef("text_basic", 1);
    /** JSON, optionally conforming to a client-authored JSON Schema (the {@link #SCHEMA} parameter). */
    public static final TemplateRef CUSTOM_JSON_V1 = new TemplateRef("custom_json", 1);
    /** A file delivered by HTTPS link. */
    public static final TemplateRef FILE_BASIC_V1 = new TemplateRef("file_basic", 1);

    /** {@code custom_json}'s schema parameter (comparison {@code enum}, open text). */
    public static final String SCHEMA = "schema";

    private GenericTemplates() {
    }
}
