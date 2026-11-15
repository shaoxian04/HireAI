package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.TaskReadAppService.FrozenDeliverable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

/**
 * Serves a DeliverySnapshot's bytes. They come from an Agent, so the response never lets a browser
 * run them on the platform's origin: always an attachment, {@code X-Content-Type-Options: nosniff},
 * {@code Content-Security-Policy: sandbox}, and a Content-Type from a fixed allowlist (the
 * file_basic types the platform recognised), {@code application/octet-stream} for anything else
 * (HTML and SVG included).
 */
public final class DeliverableResponse {

    /** Servable type → download file extension. */
    private static final Map<String, String> SERVABLE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "application/pdf", "pdf",
            "application/zip", "zip", "application/json", "json", "text/csv", "csv", "text/plain", "txt");

    private DeliverableResponse() {
    }

    public static ResponseEntity<byte[]> of(UUID taskId, FrozenDeliverable deliverable) {
        String type = deliverable.snapshot().mediaType();
        String extension = SERVABLE.get(type);
        MediaType contentType = extension == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
        String filename = "deliverable-" + taskId + "." + (extension == null ? "bin" : extension);
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(deliverable.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-HireAI-Sha256", deliverable.snapshot().sha256())
                .eTag("\"" + deliverable.snapshot().sha256() + "\"")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(deliverable.content());
    }
}
