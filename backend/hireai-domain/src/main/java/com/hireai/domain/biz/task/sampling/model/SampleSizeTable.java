package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The one lot-size → sample-size table every Template shares (spec S8, D21), after ISO 2859-1
 * normal inspection: Table I gives the sample-size code letter for the lot size and inspection level;
 * Table III-A gives the double sampling plan for that letter at the platform's AQL. Not linked to
 * Reputation, and no switching rules (tightened/reduced inspection) apply.
 *
 * <p>Where Table III-A has no double plan at this AQL (its single plan accepts on zero defects), the
 * single plan is used. Letters above the AQL column's first plan follow the arrow down to it; letters
 * below its last plan follow the arrow up. A sample as large as the lot inspects every Item.
 */
public final class SampleSizeTable {

    /** The acceptance quality limit the plans are taken at: 2.5% defective. */
    public static final BigDecimal AQL = new BigDecimal("2.5");

    /** Upper lot-size bound of each Table I row, with its letters for levels I, II, III. */
    private static final int[] LOT_UPPER = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10_000, 35_000,
            150_000, 500_000, Integer.MAX_VALUE};
    private static final String[] LETTERS = {"AAB", "ABC", "BCD", "CDE", "CEF", "DFG", "EGH", "FHJ", "GJK", "HKL",
            "JLM", "KMN", "LNP", "MPQ", "NQR"};

    /** Table II-A single sample size of each letter. */
    private static final Map<Character, Integer> SINGLE_SIZE = Map.ofEntries(
            Map.entry('A', 2), Map.entry('B', 3), Map.entry('C', 5), Map.entry('D', 8), Map.entry('E', 13),
            Map.entry('F', 20), Map.entry('G', 32), Map.entry('H', 50), Map.entry('J', 80), Map.entry('K', 125),
            Map.entry('L', 200), Map.entry('M', 315), Map.entry('N', 500), Map.entry('P', 800),
            Map.entry('Q', 1250), Map.entry('R', 2000));

    /** AQL 2.5 plans by letter: {single Ac, double n (each stage, 0 = none), Ac1, Re1, Ac2, Re2}. */
    private static final Map<Character, int[]> AQL_2_5 = Map.ofEntries(
            Map.entry('D', new int[]{0, 0, 0, 0, 0, 0}),
            Map.entry('E', new int[]{1, 8, 0, 2, 1, 2}),
            Map.entry('F', new int[]{1, 13, 0, 2, 1, 2}),
            Map.entry('G', new int[]{2, 20, 0, 3, 3, 4}),
            Map.entry('H', new int[]{3, 32, 1, 3, 4, 5}),
            Map.entry('J', new int[]{5, 50, 2, 5, 6, 7}),
            Map.entry('K', new int[]{7, 80, 3, 6, 9, 10}),
            Map.entry('L', new int[]{10, 125, 5, 9, 12, 13}),
            Map.entry('M', new int[]{14, 200, 7, 11, 18, 19}),
            Map.entry('N', new int[]{21, 315, 11, 16, 26, 27}));

    private SampleSizeTable() {
    }

    /** Table I: the sample-size code letter for a lot of {@code lotSize} Items at {@code level} (a lot of 1 reads as the 2-8 row). */
    public static char codeLetter(int lotSize, InspectionLevel level) {
        if (lotSize < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A lot has at least 1 Item; got " + lotSize);
        }
        for (int row = 0; row < LOT_UPPER.length; row++) {
            if (lotSize <= LOT_UPPER[row]) {
                return LETTERS[row].charAt(level.ordinal());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** The plan a lot of {@code lotSize} Items is inspected under at {@code level}. */
    public static SamplingPlan plan(int lotSize, InspectionLevel level) {
        char letter = effectiveLetter(codeLetter(lotSize, level));
        int[] p = AQL_2_5.get(letter);
        int singleSize = SINGLE_SIZE.get(letter);
        int singleAccept = p[0];
        if (singleSize >= lotSize) {
            return new SamplingPlan(letter, lotSize, lotSize, 0, singleAccept, singleAccept + 1, 0, 0, true);
        }
        if (p[1] == 0) {
            return new SamplingPlan(letter, lotSize, singleSize, 0, singleAccept, singleAccept + 1, 0, 0, false);
        }
        return new SamplingPlan(letter, lotSize, p[1], p[1], p[2], p[3], p[4], p[5], false);
    }

    /** The arrows of the AQL 2.5 column: A-C use D's plan, P-R use N's. */
    private static char effectiveLetter(char letter) {
        if (letter < 'D') {
            return 'D';
        }
        if (letter > 'N') {
            return 'N';
        }
        return letter;
    }
}
