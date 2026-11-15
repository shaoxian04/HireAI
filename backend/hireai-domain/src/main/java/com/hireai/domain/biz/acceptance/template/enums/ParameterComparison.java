package com.hireai.domain.biz.acceptance.template.enums;

import java.util.Locale;

/**
 * The one comparison a Template parameter declares (ADR 0009, spec D34). It fixes both the kind of
 * value a Contract holds for the parameter and how an Offer's range for it is read:
 * <ul>
 *   <li>{@code MAX}: a number; an Offer range is a bound, covering Contract values {@code <=} it.</li>
 *   <li>{@code MIN}: a number; an Offer range is a bound, covering Contract values {@code >=} it.</li>
 *   <li>{@code SUBSET}: a set of strings; an Offer range is a set, covering Contract sets inside it.</li>
 *   <li>{@code ENUM}: one string; an Offer range is a set (or any value), covering its members.</li>
 *   <li>{@code FLAG}: a boolean; an Offer range says whether the Agent accepts {@code true}.</li>
 * </ul>
 * A Contract that leaves a parameter unset is covered for it by every Offer. Applying coverage
 * (does an Offer cover a Contract) is matching's job; this enum names the rule each parameter follows.
 */
public enum ParameterComparison {
    MAX,
    MIN,
    SUBSET,
    ENUM,
    FLAG;

    /** The lowercase name used in Template definitions and the API. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ParameterComparison fromWireName(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
