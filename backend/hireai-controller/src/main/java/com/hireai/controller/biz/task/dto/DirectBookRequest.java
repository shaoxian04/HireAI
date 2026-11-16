package com.hireai.controller.biz.task.dto;

import com.hireai.controller.biz.acceptance.dto.ContractRequest;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hireai.domain.biz.task.item.info.ItemInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import com.hireai.domain.biz.task.model.ClientDeadline;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Inbound HTTP DTO for a direct booking: client hires a specific agent by id. */
public record DirectBookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull UUID agentId,
        /** Optional: a category slug the agent serves (default: the agent's first category). */
        @Size(max = 100) String category,
        /** Optional: how many seconds from booking the Client needs the result (#83). */
        @Positive(message = "deadlineSeconds must be positive")
        @Max(value = ClientDeadline.MAX_SECONDS, message = "deadlineSeconds must be at most 2592000 (30 days)")
        Long deadlineSeconds,
        /** Optional (#91): the Contract to book under; only Agents whose Offer covers it qualify. */
        @Valid ContractRequest contract,
        /**
         * Optional: the inputs of a multi-Item Task (spec S3), one per Item, 2 to 1000. With Items,
         * {@code budget} is the price of ONE Item and the Task freezes budget × the Item count.
         */
        @Size(max = 1000, message = "items: a Task can hold at most 1000 Items")
        List<@Valid @NotNull(message = "items: an Item must not be null") ItemRequest> items,
        /**
         * Optional: book under your Engagement with this Agent (ADR 0010). Only then may an Item's input be
         * a document you uploaded to it ({@code {"sourceId": ...}}).
         */
        UUID engagementId,
        /**
         * Optional (spec S10): an AI Review of these criteria, priced and frozen at booking. Offered
         * only when the Agent's Offer accepts it and the fee is at most 10% of the price.
         */
        @Valid AiReviewRequest aiReview
) {

    /** The criteria code cannot check, in the Client's words. */
    public record AiReviewRequest(
            @NotNull @Size(min = 1, max = 10, message = "aiReview.criteria: 1 to 10 criteria")
            List<@NotBlank(message = "aiReview.criteria: a criterion must not be blank")
                 @Size(max = 300, message = "aiReview.criteria: a criterion must have at most 300 characters")
                 String> criteria) {
    }

    /**
     * One Item: a string (its text input), or {@code {"input": "...", "sourceId": "<document id>"}} for
     * an Item whose input is a document uploaded to the Engagement (input optional: the file name).
     */
    public record ItemRequest(
            @Size(max = 5000, message = "items: an Item must have at most 5000 characters") String input,
            UUID sourceId) {

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public ItemRequest(@JsonProperty("input") String input, @JsonProperty("sourceId") UUID sourceId) {
            this.input = input;
            this.sourceId = sourceId;
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ItemRequest text(String input) {
            return new ItemRequest(input, null);
        }

        public ItemInput toInfo() {
            return new ItemInput(input, sourceId);
        }
    }

    /** The Items as the booking carries them. */
    public List<ItemInput> itemInputs() {
        return items == null ? null : items.stream().map(ItemRequest::toInfo).toList();
    }

}
