package com.hireai.controller.biz.agentcallback;

import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.controller.base.BaseController;
import com.hireai.utility.result.ResultCode;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Agent result-callback surface. Unlike every other endpoint this is NOT JWT-authenticated —
 * the caller is the Agent, authenticated instead by the short-lived dispatch token carried in
 * the Authorization header (Hard Invariant #6). Thin: extract the bearer token, map the body to
 * {@link AgentResultInfo}, delegate. A bad/expired/mismatched token is mapped to HTTP 401 by the
 * controller-local handler below, so the shared global handler stays untouched.
 */
@RestController
@RequestMapping("/api/agent-callbacks")
public class AgentCallbackController extends BaseController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentCallbackAppService agentCallbackAppService;

    public AgentCallbackController(AgentCallbackAppService agentCallbackAppService) {
        this.agentCallbackAppService = agentCallbackAppService;
    }

    @PostMapping("/{taskId}/result")
    public WebResult<Void> recordResult(@PathVariable("taskId") UUID taskId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @Valid @RequestBody AgentResultCallbackRequest request) {
        String token = extractBearer(authorization);
        AgentResultInfo result = new AgentResultInfo(
                request.agentStatus(), request.resultPayloadJson(), request.resultUrl(), request.message());
        agentCallbackAppService.recordResult(taskId, token, result);
        return ok();
    }

    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new DispatchTokenInvalidException("Missing or malformed Authorization header");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new DispatchTokenInvalidException("Empty bearer token");
        }
        return token;
    }

    @ExceptionHandler(DispatchTokenInvalidException.class)
    public ResponseEntity<WebResult<Void>> handleInvalidToken(DispatchTokenInvalidException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid dispatch token"));
    }
}
