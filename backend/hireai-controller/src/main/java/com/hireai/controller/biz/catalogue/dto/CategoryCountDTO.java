package com.hireai.controller.biz.catalogue.dto;

/**
 * One bucket of the platform-owned category list: its slug ({@code category}, the value every
 * category field accepts), a display label, and the number of active, listed agents in it.
 */
public record CategoryCountDTO(String category, String label, int agentCount) {
}
