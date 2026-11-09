package com.hireai.infrastructure.repository.offering.embedding;

/**
 * pgvector's text form ({@code [x,y,...]}), which every query binds and casts with
 * {@code CAST(:param AS vector)}, so no pgvector Java type or Hibernate mapping is needed.
 */
public final class PgVectors {

    private PgVectors() {
    }

    /** The literal for {@code vector}, or null for null (an absent vector stays SQL NULL). */
    public static String toLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    public static float[] parse(String literal) {
        String body = literal.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
