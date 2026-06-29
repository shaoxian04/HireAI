package com.hireai.domain.biz.adjudication.service;

/** Outcome of inspecting a result payload as JSON and (optionally) against a JSON Schema. */
public record JsonCheckResult(boolean validJson, boolean schemaApplicable,
                              boolean schemaMatches, String detail) {
}
