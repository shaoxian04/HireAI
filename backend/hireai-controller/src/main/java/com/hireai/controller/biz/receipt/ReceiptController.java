package com.hireai.controller.biz.receipt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.acceptance.receipt.ReceiptAppService;
import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.receipt.dto.ReceiptDTO;
import com.hireai.controller.config.CurrentApiKeyProvider;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signed receipts (spec S11, D40; format in docs/details/receipts.md). The list and the download are for
 * the Task's Client and the assigned Agent's Builder; the public keys are public, so anyone holding
 * a receipt can verify it without trusting HireAI's database.
 */
@RestController
public class ReceiptController extends BaseController {

    private final ReceiptAppService receiptAppService;
    private final CurrentUserProvider currentUser;
    private final CurrentApiKeyProvider currentApiKey;
    private final ObjectMapper objectMapper;

    public ReceiptController(ReceiptAppService receiptAppService, CurrentUserProvider currentUser,
                             CurrentApiKeyProvider currentApiKey, ObjectMapper objectMapper) {
        this.receiptAppService = receiptAppService;
        this.currentUser = currentUser;
        this.currentApiKey = currentApiKey;
        this.objectMapper = objectMapper;
    }

    /** The task's receipts (one per settled Item); empty while nothing has settled. */
    @GetMapping("/api/tasks/{taskId}/receipts")
    public WebResult<List<ReceiptDTO>> listForTask(@PathVariable("taskId") UUID taskId) {
        List<ReceiptDTO> receipts = new ArrayList<>();
        for (ReceiptModel receipt : receiptAppService.receiptsForTask(taskId, reader())) {
            receipts.add(toDTO(receipt));
        }
        return ok(receipts);
    }

    /** The receipt document exactly as signed: {@code {"receipt": …, "signature": …}}, as a download. */
    @GetMapping("/api/receipts/{receiptId}")
    public ResponseEntity<byte[]> download(@PathVariable("receiptId") UUID receiptId) {
        ReceiptModel receipt = receiptAppService.receipt(receiptId, reader());
        String filename = receipt.itemNo() == null
                ? "hireai-receipt-" + receipt.taskId() + ".json"
                : "hireai-receipt-" + receipt.taskId() + "-item-" + receipt.itemNo() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(receipt.envelope().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The receipt as a W3C Verifiable Credential 2.0 (media type {@code application/vc}), secured
     * with an {@code eddsa-jcs-2022} Data Integrity proof; docs/details/receipts.md#exports.
     */
    @GetMapping("/api/receipts/{receiptId}/credential")
    public ResponseEntity<byte[]> credential(@PathVariable("receiptId") UUID receiptId) {
        return export(receiptAppService.credential(receiptId, reader()), MediaType.parseMediaType("application/vc"),
                "hireai-receipt-" + receiptId + ".vc.json");
    }

    /** The receipt as OTel {@code gen_ai.evaluation.result} events, an OTLP/JSON logs export request. */
    @GetMapping("/api/receipts/{receiptId}/otel")
    public ResponseEntity<byte[]> otel(@PathVariable("receiptId") UUID receiptId) {
        return export(receiptAppService.otelEvents(receiptId, reader()), MediaType.APPLICATION_JSON,
                "hireai-receipt-" + receiptId + ".otlp.json");
    }

    private static ResponseEntity<byte[]> export(String document, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(document.getBytes(StandardCharsets.UTF_8));
    }

    /** The receipt verification keys as a JWK Set (RFC 8037 OKP keys), active key first. */
    @GetMapping("/api/receipt-keys")
    public ResponseEntity<Map<String, Object>> publicKeys() {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (ReceiptSigner.PublicKey key : receiptAppService.publishedKeys()) {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "OKP");
            jwk.put("crv", "Ed25519");
            jwk.put("alg", "EdDSA");
            jwk.put("use", "sig");
            jwk.put("kid", key.keyId());
            jwk.put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(key.raw()));
            jwk.put("status", key.active() ? "active" : "retired");
            keys.add(jwk);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(Map.of("keys", keys));
    }

    /**
     * The caller, from the server-side identity only (Invariant #5). An API key reads as its owner in
     * the Client channel; a signed-in user reads as a Builder only while holding ROLE_BUILDER.
     */
    private ReceiptAppService.Reader reader() {
        UUID userId = currentUser.currentUserId();
        if (currentApiKey.current().isPresent()) {
            return ReceiptAppService.Reader.apiKey(userId);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean builder = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BUILDER".equals(a.getAuthority()));
        return ReceiptAppService.Reader.signedIn(userId, builder);
    }

    private ReceiptDTO toDTO(ReceiptModel receipt) {
        try {
            return new ReceiptDTO(receipt.id(), receipt.taskId(), receipt.itemNo(), receipt.keyId(),
                    receipt.issuedAt(), objectMapper.readTree(receipt.body()), receipt.signature());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored receipt body is not JSON: " + receipt.id(), e);
        }
    }
}
