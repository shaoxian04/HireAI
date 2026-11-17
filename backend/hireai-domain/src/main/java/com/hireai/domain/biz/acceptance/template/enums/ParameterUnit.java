package com.hireai.domain.biz.acceptance.template.enums;

import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a numeric Template parameter counts (spec D11/D12). Code-defined like the Check Library:
 * the extractor is told each parameter's unit, and a requirement whose own words count something
 * else ("200 words" onto a character limit) is ambiguous, so it never maps.
 */
public enum ParameterUnit {
    CHARACTERS("characters", "characters?|chars?"),
    BYTES("bytes", "bytes?|[kmg]i?b|kilobytes?|megabytes?|gigabytes?"),
    PIXELS("pixels", "pixels?|px");

    /**
     * Units a client may count a deliverable in that no parameter measures. Matched only after a
     * quantity ("200 words", "two pages"), so "a landing page" names no unit.
     */
    private static final Map<String, String> OTHER_UNITS = Map.of(
            "words", "words?", "sentences", "sentences?", "lines", "lines?", "paragraphs", "paragraphs?",
            "pages", "pages?", "items", "items?", "bullet points", "bullet points?|bullets?",
            "tokens", "tokens?", "minutes", "minutes?", "seconds", "seconds?");

    private static final String QUANTITY = "(?:\\d[\\d,.]*|a few|one|two|three|four|five|six|seven|eight|nine|ten"
            + "|eleven|twelve|fifteen|twenty|thirty|forty|fifty|hundred|thousand)";

    private static final Map<String, ParameterUnit> BY_PARAMETER = Map.of(
            GenericTemplates.MIN_LENGTH, CHARACTERS,
            GenericTemplates.MAX_LENGTH, CHARACTERS,
            GenericTemplates.MAX_BYTES, BYTES,
            ImageGenerationTemplate.WIDTH, PIXELS,
            ImageGenerationTemplate.HEIGHT, PIXELS);

    private final String words;
    private final String pattern;

    ParameterUnit(String words, String pattern) {
        this.words = words;
        this.pattern = pattern;
    }

    /** The unit in plain words, for people and for the extractor's prompt. */
    public String words() {
        return words;
    }

    /** The unit a parameter is measured in; empty for one that counts nothing (a set, a flag, a tolerance). */
    public static Optional<ParameterUnit> of(String parameter) {
        return Optional.ofNullable(BY_PARAMETER.get(parameter));
    }

    /**
     * A unit other than this one that {@code text} counts something in (for example "words" in
     * "20 to 200 words" against {@link #CHARACTERS}), or empty when it names none.
     */
    public Optional<String> otherUnitNamedIn(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (var other : otherUnits()) {
            Matcher m = Pattern.compile("\\b" + QUANTITY + "(?:\\s*-\\s*|\\s+)?(?:" + other.getValue() + ")\\b")
                    .matcher(lower);
            if (m.find()) {
                return Optional.of(other.getKey());
            }
        }
        return Optional.empty();
    }

    private List<Map.Entry<String, String>> otherUnits() {
        List<Map.Entry<String, String>> out = new java.util.ArrayList<>(OTHER_UNITS.entrySet());
        for (ParameterUnit unit : values()) {
            if (unit != this) {
                out.add(Map.entry(unit.words, unit.pattern));
            }
        }
        out.sort(Map.Entry.comparingByKey());
        return out;
    }
}
