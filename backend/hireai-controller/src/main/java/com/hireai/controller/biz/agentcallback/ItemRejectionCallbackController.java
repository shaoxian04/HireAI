package com.hireai.controller.biz.agentcallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.application.biz.task.sampling.SamplingAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agentcallback.dto.AgentRejectionResponseRequest;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/**
 * The Agent's response to a buyer's reviewer rejecting one Item (spec S8, D17′): revise once, concede
 * or stand by with a reason, as for a whole-Task rejection. Authenticated exactly like the result
 * callback (Hard Invariant #6): the dispatch token the notice carried, for THIS task, plus a fresh
 * signature over the raw body with the Agent's current secret, checked before the body is parsed; the
 * signed body must name this task. 409 when the Item is not awaiting a response, the window has
 * closed, or a revision is not allowed.
 */
@RestController
@RequestMapping("/api/agent-callbacks")
public class ItemRejectionCallbackController extends BaseController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentCallbackAppService agentCallbackAppService;
    private final SamplingAppService samplingAppService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ItemRejectionCallbackController(AgentCallbackAppService agentCallbackAppService,
                                          SamplingAppService samplingAppService,
                                          ObjectMapper objectMapper, Validator validator) {
        this.agentCallbackAppService = agentCallbackAppService;
        this.samplingAppService = samplingAppService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/{taskId}/items/{itemNo}/rejection-response")
    public WebResult<Void> respondToItemRejection(
            @PathVariable("taskId") UUID taskId, @PathVariable("itemNo") int itemNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = AgentCallbackController.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {
        String token = extractBearer(authorization);
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        agentCallbackAppService.authenticate(taskId, token, signature, body);
        AgentRejectionResponseRequest request = parse(body);
        if (!taskId.equals(request.taskId())) {
            throw new DispatchTokenInvalidException(
                    "Signed response body is for task " + request.taskId() + ", not " + taskId);
        }
        samplingAppService.respondToItemRejection(taskId, itemNo, token, new AgentRejectionResponseInfo(
                request.action(), request.reason(), request.resultPayloadJson(), request.resultUrl()));
        return ok();
    }

    private AgentRejectionResponseRequest parse(byte[] body) {
        AgentRejectionResponseRequest request;
        try {
            request = body.length == 0 ? null : objectMapper.readValue(body, AgentRejectionResponseRequest.class);
        } catch (IOException ex) {
            request = null;
        }
        if (request == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Request body is missing or malformed");
        }
        Set<ConstraintViolation<AgentRejectionResponseRequest>> violations = validator.validate(request);
        violations.stream()
                .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .ifPresent(v -> {
                    throw new DomainException(ResultCode.VALIDATION_ERROR, v.getPropertyPath() + ": " + v.getMessage());
                });
        return request;
    }

    private static String extractBearer(String authorization) {
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
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid dispatch token or signature"));
    }
}
