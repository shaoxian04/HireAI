package com.hireai.domain.biz.acceptance.check.service;

import com.hireai.domain.biz.acceptance.check.model.DecodedImage;
import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.adjudication.model.CheckResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.BACKGROUND_COLOUR;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.COLOUR_TOLERANCE;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.DEFAULT_COLOUR_TOLERANCE;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.DUPLICATE_BRIEF;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.FORMATS;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.HEIGHT;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.IMAGE_BACKGROUND_COLOUR;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.IMAGE_DIMENSIONS;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.IMAGE_FORMAT;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.IMAGE_TYPES;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.MAX_DIMENSION;
import static com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate.WIDTH;

/**
 * The Check Library (spec S5, D12): the code-defined, parameterised checks a Template may list.
 * Nothing is generated at submit; a Contract only sets the parameters of entries that exist here.
 * There are two kinds of entry:
 * <ul>
 *   <li>a <b>check</b> judges only this Task's frozen delivery against its Contract and returns
 *       pass, reject or send-to-review ({@link CheckResult}). Returning nothing means the Contract
 *       does not ask for it (its parameters are unset);</li>
 *   <li>a <b>Finding</b> needs other Tasks or outside data ({@link FindingFacts}) and only ever
 *       reports a {@link Finding}: it never decides a verdict, so it never moves money (D7).</li>
 * </ul>
 * The generic Templates' checks still run in {@code ValidationDomainServiceImpl}; new Templates add
 * their entries here.
 */
public final class CheckLibrary {

    /** What a pixel check judges: the Contract and the frozen copy, with its pixels when decodable. */
    public record Subject(Contract contract, DeliverySnapshot snapshot, DecodedImage image) {
    }

    /** Types the platform decodes: one that will not decode is broken, and rejected. */
    private static final Set<String> DECODED_TYPES = Set.of("image/png", "image/jpeg");
    /** Types the Template allows that the platform cannot decode: their pixel checks go to review. */
    private static final Set<String> REVIEWED_TYPES = Set.of("image/webp");

    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    /** Pixels at least this opaque count towards the background colour. */
    private static final int OPAQUE_ALPHA = 128;

    private static final Map<String, Function<Subject, Optional<CheckResult>>> CHECKS = new LinkedHashMap<>();
    private static final Map<String, BiFunction<Subject, FindingFacts, Optional<Finding>>> FINDINGS = new LinkedHashMap<>();

    static {
        CHECKS.put(IMAGE_FORMAT, CheckLibrary::imageFormat);
        CHECKS.put(IMAGE_DIMENSIONS, CheckLibrary::imageDimensions);
        CHECKS.put(IMAGE_BACKGROUND_COLOUR, CheckLibrary::backgroundColour);
        FINDINGS.put(DUPLICATE_BRIEF, CheckLibrary::duplicateBrief);
    }

    private CheckLibrary() {
    }

    public static boolean isCheck(String entry) {
        return CHECKS.containsKey(entry);
    }

    public static boolean isFinding(String entry) {
        return FINDINGS.containsKey(entry);
    }

    /** Runs one check entry; empty when the Contract does not ask for it. */
    public static Optional<CheckResult> check(String entry, Subject subject) {
        Function<Subject, Optional<CheckResult>> check = CHECKS.get(entry);
        if (check == null) {
            throw new IllegalStateException("No Check Library check named " + entry);
        }
        return check.apply(subject);
    }

    /** Runs one Finding entry; empty when there is nothing to report. */
    public static Optional<Finding> find(String entry, Subject subject, FindingFacts facts) {
        BiFunction<Subject, FindingFacts, Optional<Finding>> finding = FINDINGS.get(entry);
        if (finding == null) {
            throw new IllegalStateException("No Check Library Finding named " + entry);
        }
        return finding.apply(subject, facts);
    }

    /** True when {@code value} is a colour the background check can read: {@code #rrggbb}. */
    public static boolean isHexColour(String value) {
        return value != null && HEX_COLOUR.matcher(value).matches();
    }

    // ---- image_generation ----

    private static Optional<CheckResult> imageFormat(Subject s) {
        Set<String> asked = textSet(s.contract(), FORMATS);
        Set<String> accepted = asked != null ? asked : Set.copyOf(IMAGE_TYPES);
        String type = s.snapshot().mediaType();
        String list = accepted.stream().sorted().collect(Collectors.joining(", "));
        if (!accepted.contains(type)) {
            return Optional.of(new CheckResult(IMAGE_FORMAT, false, "the file is " + type + "; accepted: " + list));
        }
        // A type the platform decodes must decode: a file whose header or pixel data is broken is not
        // the image it claims to be. This fails closed, so a corrupt file is reworked, never reviewed.
        if (DECODED_TYPES.contains(type) && (s.image() == null || s.image().isCorrupt())) {
            return Optional.of(new CheckResult(IMAGE_FORMAT, false, "the file looks like " + type + " but "
                    + (s.image() == null ? "its header" : "its pixel data") + " could not be read; accepted: " + list));
        }
        // The Template's own bound applies whatever the Contract asks: a canvas declared beyond it is
        // never decoded, so it is rejected here rather than leaving its pixels unchecked.
        DecodedImage image = s.image();
        if (image != null && (image.width() > MAX_DIMENSION || image.height() > MAX_DIMENSION)) {
            return Optional.of(new CheckResult(IMAGE_FORMAT, false, "the image declares " + image.width() + " x "
                    + image.height() + " px; at most " + MAX_DIMENSION + " px a side is allowed"));
        }
        return Optional.of(new CheckResult(IMAGE_FORMAT, true, "the file is " + type + "; accepted: " + list));
    }

    /**
     * When the pixels are missing: only a type the Template allows but the platform cannot decode
     * (WebP) is left to the Client's review; anything else, including a PNG or JPEG that would not
     * decode, is rejected, so a deliberately broken file can never reach review (and its silent
     * auto-accept) instead of a rework.
     */
    private static CheckResult unreadable(String rule, Subject s, String asked) {
        String type = s.snapshot().mediaType();
        if (REVIEWED_TYPES.contains(type)) {
            return CheckResult.review(rule, "the platform cannot read the pixels of a " + type
                    + " file, so you judge it; " + asked + " asked");
        }
        return new CheckResult(rule, false, "the pixels of this " + type + " file could not be read; "
                + asked + " asked");
    }

    private static Optional<CheckResult> imageDimensions(Subject s) {
        BigDecimal width = number(s.contract(), WIDTH);
        BigDecimal height = number(s.contract(), HEIGHT);
        if (width == null && height == null) {
            return Optional.empty();
        }
        String wanted = (width == null ? "any" : width.toPlainString()) + " x "
                + (height == null ? "any" : height.toPlainString()) + " px";
        DecodedImage image = s.image();
        if (image == null) {
            return Optional.of(unreadable(IMAGE_DIMENSIONS, s, wanted));
        }
        // A corrupt image still has the size its header declares: judge it on that.
        // compareTo, not intValueExact: a value that is no whole number is refused at submit, and
        // even one that slipped past would simply not match, never throw inside the gate.
        boolean ok = (width == null || width.compareTo(BigDecimal.valueOf(image.width())) == 0)
                && (height == null || height.compareTo(BigDecimal.valueOf(image.height())) == 0);
        return Optional.of(new CheckResult(IMAGE_DIMENSIONS, ok,
                image.width() + " x " + image.height() + " px; " + wanted + " asked"));
    }

    private static Optional<CheckResult> backgroundColour(Subject s) {
        String wanted = text(s.contract(), BACKGROUND_COLOUR);
        if (wanted == null) {
            return Optional.empty();
        }
        BigDecimal toleranceParam = number(s.contract(), COLOUR_TOLERANCE);
        BigDecimal tolerance = toleranceParam == null ? DEFAULT_COLOUR_TOLERANCE : toleranceParam;
        String asked = wanted.toLowerCase(Locale.ROOT) + " (within " + tolerance.stripTrailingZeros().toPlainString()
                + " per channel)";
        DecodedImage image = s.image();
        if (image != null && image.pixels() == DecodedImage.Pixels.TOO_LARGE) {
            // Never review: a canvas too large to decode could otherwise dodge this check.
            return Optional.of(new CheckResult(IMAGE_BACKGROUND_COLOUR, false, "the image (" + image.width() + " x "
                    + image.height() + " px) is too large for its background to be checked; " + asked + " asked"));
        }
        if (image == null || !image.hasPixels()) {
            return Optional.of(unreadable(IMAGE_BACKGROUND_COLOUR, s, asked));
        }
        long r = 0, g = 0, b = 0;
        int opaque = 0;
        int[] border = image.borderArgb();
        for (int argb : border) {
            if ((argb >>> 24) >= OPAQUE_ALPHA) {
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                opaque++;
            }
        }
        if (opaque * 2 < border.length) {
            return Optional.of(new CheckResult(IMAGE_BACKGROUND_COLOUR, false,
                    "the background is transparent; " + asked + " asked"));
        }
        int[] got = {(int) Math.round((double) r / opaque), (int) Math.round((double) g / opaque),
                (int) Math.round((double) b / opaque)};
        int[] want = rgb(wanted);
        int distance = 0;
        for (int i = 0; i < 3; i++) {
            distance = Math.max(distance, Math.abs(got[i] - want[i]));
        }
        String measured = String.format(Locale.ROOT, "#%02x%02x%02x", got[0], got[1], got[2]);
        return Optional.of(new CheckResult(IMAGE_BACKGROUND_COLOUR, BigDecimal.valueOf(distance).compareTo(tolerance) <= 0,
                "the background (the image's edge) is " + measured + "; " + asked + " asked"));
    }

    private static Optional<Finding> duplicateBrief(Subject s, FindingFacts facts) {
        List<UUID> earlier = facts.earlierTasksWithSameBrief();
        if (earlier.isEmpty()) {
            return Optional.empty();
        }
        String ids = earlier.stream().map(UUID::toString).collect(Collectors.joining(", "));
        return Optional.of(new Finding(DUPLICATE_BRIEF, "You submitted the same title and description before, on task "
                + ids + ". Check you are not paying twice for one image; this does not change the Agent's pay."));
    }

    private static int[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    private static String text(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.Text t && !t.value().isBlank() ? t.value() : null;
    }

    private static Set<String> textSet(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.TextSet t ? t.values() : null;
    }

    private static BigDecimal number(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.Number n ? n.value() : null;
    }
}
