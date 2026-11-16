package com.hireai.controller.biz.task;

import com.hireai.application.biz.acceptance.aireview.AiReviewAppService;
import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.controller.biz.acceptance.converter.ContractRequest2ModelConverter;
import com.hireai.controller.biz.acceptance.dto.ContractRequest;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.acceptance.aireview.enums.AiReviewStatus;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewPolicy;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewQuote;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI Review (spec S10): the quote a booking screen shows before the Client opts in, and the Task's
 * review afterwards (an advisory pre-review report on the web; the final verdict on API/MCP).
 */
@RestController
@RequestMapping("/api/tasks")
public class AiReviewController extends BaseController {

    private final DirectBookingAppService directBookingAppService;
    private final AiReviewAppService aiReviewAppService;
    private final AiReviewPolicy aiReviewPolicy;
    private final CurrentUserProvider currentUser;

    public AiReviewController(DirectBookingAppService directBookingAppService, AiReviewAppService aiReviewAppService,
                              AiReviewPolicy aiReviewPolicy, CurrentUserProvider currentUser) {
        this.directBookingAppService = directBookingAppService;
        this.aiReviewAppService = aiReviewAppService;
        this.aiReviewPolicy = aiReviewPolicy;
        this.currentUser = currentUser;
    }

    /** What an AI Review would cost on this booking, and whether it is offered (never a 400 for "not offered"). */
    @PostMapping("/direct/ai-review-quote")
    public WebResult<QuoteDTO> quote(@Valid @RequestBody QuoteRequest request) {
        currentUser.currentUserId(); // authenticated callers only
        AiReviewQuote quote = directBookingAppService.quoteAiReview(request.agentId(),
                ContractRequest2ModelConverter.toModel(request.contract()), Money.of(request.budget()),
                request.criteria());
        return ok(new QuoteDTO(quote.fee().value(), quote.maxFee().value(), quote.available(), quote.reason(),
                aiReviewPolicy.maxCriteria()));
    }

    /** The Task's AI Review, owner-only (Invariant #5): another client, or a Task without one, gets 404. */
    @GetMapping("/{id}/ai-review")
    public WebResult<AiReviewDTO> get(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        AiReviewModel review = aiReviewAppService.findForClient(id, clientId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No AI Review for task: " + id));
        boolean completed = review.status() == AiReviewStatus.COMPLETED;
        return ok(new AiReviewDTO(review.status().name(), review.decisive(), review.fee().value(),
                review.criteria(),
                review.verdicts().stream().map(v -> new VerdictDTO(v.criterion(), v.passed(), v.reason())).toList(),
                completed ? review.rulingCategory().name() : null, review.model(), review.attempts(),
                review.requestedAt(), review.completedAt()));
    }

    public record QuoteRequest(
            @NotNull UUID agentId,
            @NotNull
            @DecimalMin(value = "0.01", message = "budget must be positive")
            @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
            BigDecimal budget,
            @Min(value = 1, message = "criteria: at least 1") @Max(value = 10, message = "criteria: at most 10")
            int criteria,
            @Valid ContractRequest contract) {
    }

    /** {@code fee}: what it would cost; {@code maxFee}: the gate (10% of the price); {@code reason}: why not offered. */
    public record QuoteDTO(BigDecimal fee, BigDecimal maxFee, boolean available, String reason, int maxCriteria) {
    }

    /**
     * {@code status}: FROZEN (waiting for a result that passes its checks), REQUESTED (the reviewer is
     * judging it), COMPLETED, FAILED (no verdict; fee refunded) or RELEASED (nothing reviewed; fee
     * refunded). {@code decisive}: the verdict settles the Task (API/MCP) rather than advising (web).
     */
    public record AiReviewDTO(String status, boolean decisive, BigDecimal fee, List<String> criteria,
                              List<VerdictDTO> verdicts, String rulingCategory, String model, int attempts,
                              Instant requestedAt, Instant completedAt) {
    }

    public record VerdictDTO(String criterion, boolean passed, String reason) {
    }
}
