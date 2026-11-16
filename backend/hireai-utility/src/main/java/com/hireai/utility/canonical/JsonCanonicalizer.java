package com.hireai.utility.canonical;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) over a plain Java tree: {@link Map} (string keys),
 * {@link Collection}, {@link String}, {@link Number}, {@link Boolean} and {@code null}. Object
 * members are sorted by their UTF-16 code units, strings use the minimal JSON escaping, numbers are
 * IEEE-754 doubles written as ECMAScript's {@code Number.prototype.toString} writes them, and there
 * is no whitespace. Used to hash Contracts and to sign receipts (spec D40): equal data always
 * yields the same bytes, whatever produced it.
 */
public final class JsonCanonicalizer {

    private static final double MAX_SAFE_INTEGER = 9007199254740991d;

    private JsonCanonicalizer() {
    }

    public static String canonicalize(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof CharSequence s) {
            writeString(s.toString(), out);
        } else if (value instanceof Number n) {
            out.append(number(n));
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(element, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String s)) {
                throw new IllegalArgumentException("JSON object keys must be strings: " + key);
            }
            keys.add(s);
        }
        keys.sort(String::compareTo); // String order is UTF-16 code-unit order, as RFC 8785 §3.2.3 requires
        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(keys.get(i), out);
            out.append(':');
            write(map.get(keys.get(i)), out);
        }
        out.append('}');
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String number(Number n) {
        double d;
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger) {
            BigInteger exact = n instanceof BigInteger b ? b : BigInteger.valueOf(n.longValue());
            if (exact.abs().compareTo(BigInteger.valueOf((long) MAX_SAFE_INTEGER)) > 0) {
                throw new IllegalArgumentException("Integer outside the IEEE-754 safe range: " + n);
            }
            d = exact.doubleValue();
        } else if (n instanceof BigDecimal b) {
            d = b.doubleValue();
        } else {
            d = n.doubleValue();
        }
        return ecmaScript(d);
    }

    /** ECMAScript Number::toString (ECMA-262 §6.1.6.1.20) on Java's shortest round-trip digits. */
    static String ecmaScript(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON has no NaN or Infinity");
        }
        if (d == 0) {
            return "0"; // also -0
        }
        String sign = d < 0 ? "-" : "";
        // Since JDK 19, Double.toString yields the shortest digits that round-trip (Ryu-like).
        // Java, unlike ECMAScript, always renders at least two digits ("4.9E-324", not "5E-324"), so
        // when one digit already round-trips, use it.
        BigDecimal exact = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        if (exact.precision() == 2) {
            BigDecimal oneDigit = exact.round(new java.math.MathContext(1, java.math.RoundingMode.HALF_EVEN));
            if (oneDigit.doubleValue() == Math.abs(d)) {
                exact = oneDigit.stripTrailingZeros();
            }
        }
        String digits = exact.unscaledValue().toString();
        int k = digits.length();
        int n = k - exact.scale(); // value = digits × 10^(n − k)
        StringBuilder out = new StringBuilder(sign);
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(exponent < 0 ? "-" : "+").append(Math.abs(exponent));
        }
        return out.toString();
    }
}
