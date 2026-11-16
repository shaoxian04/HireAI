package com.hireai.controller.biz.agentcallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.utility.exception.DomainException;
import com.hireai.controller.base.BaseController;
import com.hireai.utility.result.ResultCode;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agentcallback.dto.AgentRejectionResponseRequest;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.item.info.AgentItemResultInfo;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent result-callback surface. Unlike every other endpoint this is NOT JWT-authenticated:
 * the caller is the Agent, authenticated instead by the dispatch token in the Authorization
 * header plus an {@code X-HireAI-Signature} over the raw body made with its signing secret
 * (Hard Invariant #6). The body is taken as raw bytes and authenticated BEFORE it is parsed, so
 * nothing from an unauthenticated caller reaches Jackson. Any authentication failure is mapped to
 * HTTP 401 by the controller-local handler below, so the shared global handler stays untouched.
 */
@RestController
@RequestMapping("/api/agent-callbacks")
public class AgentCallbackController extends BaseController {

    public static final String SIGNATURE_HEADER = "X-HireAI-Signature";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentCallbackAppService agentCallbackAppService;
    private final RejectionAppService rejectionAppService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AgentCallbackController(AgentCallbackAppService agentCallbackAppService,
                                   RejectionAppService rejectionAppService,
                                   ObjectMapper objectMapper, Validator validator) {
        this.agentCallbackAppService = agentCallbackAppService;
        this.rejectionAppService = rejectionAppService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/{taskId}/result")
    public WebResult<Void> recordResult(@PathVariable("taskId") UUID taskId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                        @RequestBody(required = false) byte[] rawBody) {
        String token = extractBearer(authorization);
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        agentCallbackAppService.authenticate(taskId, token, signature, body);
        AgentResultCallbackRequest request = parse(body, AgentResultCallbackRequest.class);
        // The signature covers the body, and the body names its task: binding it to the path (and
        // so to the token, which authenticate matched to the path) stops a signed result for one
        // task being replayed onto another task of the same Agent.
        if (!taskId.equals(request.taskId())) {
            throw new DispatchTokenInvalidException(
                    "Signed callback body is for task " + request.taskId() + ", not " + taskId);
        }
        List<SelfReportedAnomaly> anomalies = anomaliesOf(request.anomalies());
        AgentResultInfo result = new AgentResultInfo(
                request.agentStatus(), payloadOf(request), request.resultUrl(), request.message(),
                request.hasItems() ? request.items().stream()
                        .map(i -> new AgentItemResultInfo(i.itemNo(), i.agentStatus(), i.resultPayloadJson(),
                                i.resultUrl(), anomaliesOf(i.anomalies())))
                        .toList() : List.of(),
                anomalies);

        agentCallbackAppService.recordResult(taskId, token, result);
        return ok();
    }

    private static List<SelfReportedAnomaly> anomaliesOf(List<AgentResultCallbackRequest.Anomaly> reported) {
        return reported == null ? List.of()
                : reported.stream().map(a -> new SelfReportedAnomaly(a.check(), a.description())).toList();
    }

    /**
     * The Task-level payload. A single-deliverable result must carry one; a multi-Item callback may
     * leave it out, and the Task's result record then keeps the Items as the Agent sent them.
     */
    private String payloadOf(AgentResultCallbackRequest request) {
        if (request.resultPayloadJson() != null && !request.resultPayloadJson().isBlank()) {
            return request.resultPayloadJson();
        }
        if (!request.hasItems()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "resultPayloadJson: must not be blank");
        }
        try {
            return objectMapper.writeValueAsString(request.items());
        } catch (IOException ex) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "items: could not be read");
        }
    }

    /**
     * The Agent's response to a rejection notice (#95): revise once, concede, or stand by with a
     * reason. Authenticated exactly like the result callback (Hard Invariant #6): the dispatch token
     * the notice carried, for THIS task, plus a fresh signature over the raw body with the Agent's
     * current secret, checked before the body is parsed; the signed body must name this task.
     * 409 when the task is not awaiting a response, the window has closed, or a revision is not allowed.
     */
    @PostMapping("/{taskId}/rejection-response")
    public WebResult<Void> respondToRejection(@PathVariable("taskId") UUID taskId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                              @RequestBody(required = false) byte[] rawBody) {
        String token = extractBearer(authorization);
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        agentCallbackAppService.authenticate(taskId, token, signature, body);
        AgentRejectionResponseRequest request = parse(body, AgentRejectionResponseRequest.class);
        if (!taskId.equals(request.taskId())) {
            throw new DispatchTokenInvalidException(
                    "Signed response body is for task " + request.taskId() + ", not " + taskId);
        }
        rejectionAppService.respond(taskId, token, new AgentRejectionResponseInfo(
                request.action(), request.reason(), request.resultPayloadJson(), request.resultUrl()));
        return ok();
    }

    private <T> T parse(byte[] body, Class<T> type) {
        T request;
        try {
            request = body.length == 0 ? null : objectMapper.readValue(body, type);
        } catch (IOException ex) {
            request = null;
        }
        if (request == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Request body is missing or malformed");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        violations.stream()
                .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .ifPresent(v -> {
                    throw new DomainException(ResultCode.VALIDATION_ERROR,
                            v.getPropertyPath() + ": " + v.getMessage());
                });
        return request;
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
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid dispatch token or signature"));
    }
}
