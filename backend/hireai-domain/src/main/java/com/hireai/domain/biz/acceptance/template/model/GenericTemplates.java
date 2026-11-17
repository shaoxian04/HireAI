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
    /** {@code file_basic}'s optional largest file in bytes (comparison {@code min}). */
    public static final String MAX_BYTES = "max_bytes";
    /** {@code file_basic}'s optional accepted media types (comparison {@code subset}). */
    public static final String MEDIA_TYPES = "media_types";

    /** Parameters holding a whole number (characters, bytes); anything else is refused at submit. */
    public static final java.util.Set<String> WHOLE_NUMBER_PARAMETERS = java.util.Set.of(MIN_LENGTH, MAX_LENGTH, MAX_BYTES);

    private GenericTemplates() {
    }
}
