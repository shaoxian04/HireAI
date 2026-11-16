package com.hireai.controller.biz.engagement;

import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService.Download;
import com.hireai.controller.base.WebResult;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.utility.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * An Item's signed source download link (spec S4, D31): {@code GET /api/source-links/{token}}. The token
 * is the only credential (no JWT): it names one Item and expires at the execution deadline. Every attempt
 * with a genuine token is logged. A forged or malformed token, or an expired one, is 401; content deleted
 * by retention is 410. The bytes are served as an attachment that a browser never runs.
 */
@RestController
@RequestMapping("/api/source-links")
public class SourceLinkController {

    /** Types served as themselves; anything else (HTML and SVG included) is application/octet-stream. */
    private static final Set<String> SERVABLE = Set.of("image/png", "image/jpeg", "image/webp", "image/gif",
            "application/pdf", "application/zip", "application/json", "text/csv", "text/plain");

    private final SourceSnapshotAppService sourceSnapshotAppService;

    public SourceLinkController(SourceSnapshotAppService sourceSnapshotAppService) {
        this.sourceSnapshotAppService = sourceSnapshotAppService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> download(@PathVariable("token") String token, HttpServletRequest request) {
        Download download = sourceSnapshotAppService.download(token, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT));
        return switch (download) {
            case Download.Served served -> serve(served.snapshot(), served.content());
            case Download.Refused refused -> switch (refused.reason()) {
                case INVALID -> refuse(HttpStatus.UNAUTHORIZED, "This link is not valid");
                case EXPIRED -> refuse(HttpStatus.UNAUTHORIZED, "This link expired at the execution deadline");
                case RETIRED -> refuse(HttpStatus.GONE,
                        "This document was deleted at the end of its retention period; only its hash is kept");
            };
        };
    }

    private static ResponseEntity<byte[]> serve(SourceSnapshotModel s, byte[] content) {
        MediaType type = SERVABLE.contains(s.mediaType())
                ? MediaType.parseMediaType(s.mediaType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(s.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-HireAI-Sha256", s.sha256())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(content);
    }

    private static ResponseEntity<WebResult<Void>> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(WebResult.error(ResultCode.VALIDATION_ERROR, message));
    }
}
