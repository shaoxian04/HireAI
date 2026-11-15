package com.hireai.domain.biz.acceptance.template.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * {@code image_generation@1}, the first non-generic Template (spec S5). An image delivered by HTTPS
 * link, frozen as a DeliverySnapshot and judged on its pixels: format, dimensions and background
 * colour. Its definition is seeded by Flyway ({@code contract_templates}); these constants name it
 * and its Check Library entries, in the order they run.
 */
public final class ImageGenerationTemplate {

    public static final TemplateRef IMAGE_GENERATION_V1 = new TemplateRef("image_generation", 1);

    /** Accepted image types (comparison {@code subset}); unset accepts every type below. */
    public static final String FORMATS = "formats";
    /** Exact width in pixels (comparison {@code max}: an Offer names the widest it can draw). */
    public static final String WIDTH = "width";
    /** Exact height in pixels (comparison {@code max}). */
    public static final String HEIGHT = "height";
    /** Background colour as {@code #rrggbb} (comparison {@code enum}, open text). */
    public static final String BACKGROUND_COLOUR = "background_colour";
    /** Largest per-channel difference from the background colour still accepted (comparison {@code min}). */
    public static final String COLOUR_TOLERANCE = "colour_tolerance";

    public static final List<String> IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    public static final BigDecimal DEFAULT_COLOUR_TOLERANCE = BigDecimal.valueOf(32);

    public static final String IMAGE_FORMAT = "IMAGE_FORMAT";
    public static final String IMAGE_DIMENSIONS = "IMAGE_DIMENSIONS";
    public static final String IMAGE_BACKGROUND_COLOUR = "IMAGE_BACKGROUND_COLOUR";
    /** A Finding: the Client already submitted this very brief (needs other Tasks, so never moves money). */
    public static final String DUPLICATE_BRIEF = "DUPLICATE_BRIEF";

    /**
     * The checks a self-reported anomaly may send to review instead of rejecting (D7), as the seeded
     * definition's {@code anomalyEligible} lists them: content checks only. The link, fetch, format
     * and size checks are never softened, so a wrong file always goes back for rework.
     */
    public static final Set<String> ANOMALY_ELIGIBLE = Set.of(IMAGE_BACKGROUND_COLOUR);

    /** Parameters holding a whole number of pixels or colour levels; anything else is refused at submit. */
    public static final Set<String> WHOLE_NUMBER_PARAMETERS = Set.of(WIDTH, HEIGHT, COLOUR_TOLERANCE);

    /** The largest width or height the Template allows, and so the largest image the platform decodes. */
    public static final int MAX_DIMENSION = 4096;

    /** The Template's Check Library entries, as its seeded definition lists them. */
    public static final List<String> CHECKS = List.of("FORMAT_FILE_HTTPS_URL", "FILE_FETCHED", IMAGE_FORMAT,
            IMAGE_DIMENSIONS, IMAGE_BACKGROUND_COLOUR, DUPLICATE_BRIEF);

    private ImageGenerationTemplate() {
    }
}
