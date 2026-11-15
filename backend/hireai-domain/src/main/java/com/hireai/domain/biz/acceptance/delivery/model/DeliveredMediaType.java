package com.hireai.domain.biz.acceptance.delivery.model;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides what a delivered file is. The Agent's {@code Content-Type} is a claim, so the bytes win
 * wherever they can be recognised:
 * <ul>
 *   <li>PNG, JPEG, WebP, GIF, PDF and ZIP are recognised by their magic bytes, whatever was declared;</li>
 *   <li>a declaration of one of those types whose bytes do not match is not believed;</li>
 *   <li>HTML is recognised by its opening tag;</li>
 *   <li>other text (valid UTF-8, no NUL) keeps a declared text type (CSV, JSON, …), else is {@code text/plain};</li>
 *   <li>other binary keeps a declared type the platform cannot verify (e.g. {@code video/mp4}), else is
 *       {@code application/octet-stream}.</li>
 * </ul>
 * Framework-free and deterministic: the same bytes and header always give the same answer.
 */
public final class DeliveredMediaType {

    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_HTML = "text/html";

    private static final Pattern ESSENCE = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

    /** The types this class can recognise by their bytes, so a bare declaration of them is not trusted. */
    private static final Set<String> SNIFFABLE = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "application/zip", TEXT_HTML);

    private DeliveredMediaType() {
    }

    public static String determine(String declaredContentType, byte[] content) {
        String declared = essence(declaredContentType);
        String sniffed = sniffBinary(content);
        if (sniffed != null) {
            return sniffed;
        }
        if (isText(content)) {
            if (looksLikeHtml(content)) {
                return TEXT_HTML;
            }
            return declared != null && isTextType(declared) && !SNIFFABLE.contains(declared) ? declared : TEXT_PLAIN;
        }
        return declared != null && !isTextType(declared) && !SNIFFABLE.contains(declared) ? declared : OCTET_STREAM;
    }

    /** The {@code type/subtype} of a Content-Type header, lower-cased, or null when absent or malformed. */
    public static String essence(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String essence = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return ESSENCE.matcher(essence).matches() ? essence : null;
    }

    private static String sniffBinary(byte[] b) {
        if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(b, 'G', 'I', 'F', '8')) return "image/gif";
        if (startsWith(b, '%', 'P', 'D', 'F', '-')) return "application/pdf";
        if (startsWith(b, 'P', 'K', 3, 4) || startsWith(b, 'P', 'K', 5, 6)) return "application/zip";
        if (b.length >= 12 && startsWith(b, 'R', 'I', 'F', 'F')
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != (prefix[i] & 0xFF)) return false;
        }
        return true;
    }

    private static boolean isText(byte[] content) {
        for (byte b : content) {
            if (b == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean looksLikeHtml(byte[] content) {
        int len = Math.min(content.length, 512);
        String head = new String(content, 0, len, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("﻿")) head = head.substring(1).stripLeading();
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head")
                || head.startsWith("<body");
    }

    private static boolean isTextType(String essence) {
        return essence.startsWith("text/") || essence.equals("application/json") || essence.endsWith("+json")
                || essence.equals("application/xml") || essence.endsWith("+xml");
    }
}
