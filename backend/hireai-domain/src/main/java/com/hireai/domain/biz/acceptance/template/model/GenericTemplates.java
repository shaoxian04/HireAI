package com.hireai.domain.biz.acceptance.template.model;

/**
 * The generic Templates every existing output spec maps onto (spec D39). Their definitions are
 * seeded by Flyway ({@code contract_templates}); these constants only name them.
 */
public final class GenericTemplates {

    /** Plain text: the deliverable is non-blank text, optionally within {@link #MIN_LENGTH}..{@link #MAX_LENGTH}. */
    public static final TemplateRef TEXT_BASIC_V1 = new TemplateRef("text_basic", 1);
    /** JSON, optionally conforming to a client-authored JSON Schema (the {@link #SCHEMA} parameter). */
    public static final TemplateRef CUSTOM_JSON_V1 = new TemplateRef("custom_json", 1);
    /** A file delivered by HTTPS link. */
    public static final TemplateRef FILE_BASIC_V1 = new TemplateRef("file_basic", 1);

    /** {@code custom_json}'s schema parameter (comparison {@code enum}, open text). */
    public static final String SCHEMA = "schema";
    /** {@code text_basic}'s optional fewest characters (comparison {@code max}). */
    public static final String MIN_LENGTH = "min_length";
    /** {@code text_basic}'s optional most characters (comparison {@code min}). */
    public static final String MAX_LENGTH = "max_length";

    private GenericTemplates() {
    }
}
