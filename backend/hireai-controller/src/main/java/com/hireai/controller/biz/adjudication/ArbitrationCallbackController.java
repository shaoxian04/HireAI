package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.dto.ArbitrationRulingRequest;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Arbitration ruling callback surface. After asynchronous arbitration, the external worker POSTs
 * the ruling here. Not JWT-authenticated — gated instead by a shared secret in the Authorization
 * header (Hard Invariant #6). Thin: auth + map body → {@link RulingInfo} + delegate.
 *
 * <p>First-ruling-wins idempotency is owned entirely by {@link DisputeAppService#applyRuling};
 * this controller does not re-check dispute state.</p>
 */
@RestController
@RequestMapping("/api/arbitration-callbacks")
public class ArbitrationCallbackController extends BaseController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final DisputeAppService disputeAppService;
    private final String callbackSecret;

    public ArbitrationCallbackController(
            DisputeAppService disputeAppService,
            @Value("${hireai.arbitration.callback-secret}") String callbackSecret) {
        this.disputeAppService = disputeAppService;
        this.callbackSecret = callbackSecret;
    }

    @PostMapping("/{disputeId}/ruling")
    public WebResult<Void> applyRuling(
            @PathVariable("disputeId") UUID disputeId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ArbitrationRulingRequest request) {
        verifySecret(authorization);
        RulingCategory category = RulingCategory.valueOf(request.category());
        disputeAppService.applyRuling(disputeId, new RulingInfo(category, request.rationale()));
        return ok();
    }

    private void verifySecret(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new ArbitrationAuthException("Missing or malformed Authorization header");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new ArbitrationAuthException("Empty bearer token");
        }
        boolean match = MessageDigest.isEqual(
                callbackSecret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new ArbitrationAuthException("Invalid arbitration callback secret");
        }
    }

    @ExceptionHandler(ArbitrationAuthException.class)
    public ResponseEntity<WebResult<Void>> handleAuthException(ArbitrationAuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid arbitration callback secret"));
    }

    /** Maps an unrecognised {@link RulingCategory} name from the request body to HTTP 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<WebResult<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid ruling category: " + ex.getMessage()));
    }
}
