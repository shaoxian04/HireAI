package com.hireai.utility.canonical;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Expected values are RFC 8785's own examples (§3.2.2, §3.2.3, Appendix B). */
class JsonCanonicalizerTest {

    @Test
    void canonicalisesTheRfcSampleObject() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("numbers", List.of(333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001));
        // The RFC input string, spelled out with plain escapes (javac pre-processes unicode escapes).
        input.put("string", "€$" + (char) 0x0F + "\nA'B\"\\\\\"/");
        input.put("literals", Arrays.asList(null, true, false));

        assertThat(JsonCanonicalizer.canonicalize(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @Test
    void sortsPropertiesByUtf16CodeUnits() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("\u20ac", "Euro Sign");
        input.put("\r", "Carriage Return");
        input.put("\ufb33", "Hebrew Letter Dalet With Dagesh");
        input.put("1", "One");
        input.put("\ud83d\ude00", "Emoji: Grinning Face");
        input.put("\u0080", "Control");
        input.put("\u00f6", "Latin Small Letter O With Diaeresis");

        assertThat(JsonCanonicalizer.canonicalize(input)).isEqualTo(
                "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\","
                        + "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\","
                        + "\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}");
    }

    @Test
    void serialisesNumbersAsEcmaScriptDoes() {
        assertThat(number(0x0000000000000000L)).isEqualTo("0");
        assertThat(number(0x8000000000000000L)).isEqualTo("0");
        assertThat(number(0x0000000000000001L)).isEqualTo("5e-324");
        assertThat(number(0x7fefffffffffffffL)).isEqualTo("1.7976931348623157e+308");
        assertThat(number(0x4340000000000000L)).isEqualTo("9007199254740992");
        assertThat(number(0xc340000000000000L)).isEqualTo("-9007199254740992");
        assertThat(number(0x4430000000000000L)).isEqualTo("295147905179352830000");
        assertThat(number(0x44b52d02c7e14af6L)).isEqualTo("1e+23");
        assertThat(number(0x3eb0c6f7a0b5ed8dL)).isEqualTo("0.000001");
        assertThat(number(0x444b1ae4d6e2ef50L)).isEqualTo("1e+21");
        assertThat(number(0x444b1ae4d6e2ef4fL)).isEqualTo("999999999999999900000");
    }

    @Test
    void treatsIntegralJavaNumbersAsTheirDoubleValue() {
        assertThat(JsonCanonicalizer.canonicalize(List.of(7, 42L, new BigDecimal("4.50"))))
                .isEqualTo("[7,42,4.5]");
    }

    @Test
    void refusesWhatJsonCannotHold() {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(9007199254740993L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String number(long bits) {
        return JsonCanonicalizer.canonicalize(Double.longBitsToDouble(bits));
    }
}
