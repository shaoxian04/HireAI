package com.hireai.application.biz.auth;

/** Inbound carrier for a registration attempt. Built by the controller from the validated DTO. */
public record RegisterInfo(String email, String password, String displayName) {
}
