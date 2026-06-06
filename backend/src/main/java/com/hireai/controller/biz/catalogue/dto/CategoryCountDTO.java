package com.hireai.controller.biz.catalogue.dto;

/** Capability category with the number of active, listed agents in it. */
public record CategoryCountDTO(String category, int agentCount) {
}
