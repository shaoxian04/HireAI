package com.hireai.controller.biz.acceptance.dto;

/**
 * {@code POST /api/contracts/draft}'s answer: the Contract the description would be held to, and how
 * each requirement was mapped. Nothing is created or frozen; send {@code contract} (edited or not) back
 * as a submit's {@code contract} to submit in mode 2.
 */
public record ContractDraftDTO(ContractDTO contract, ExtractionDTO extraction) {
}
