package com.hireai.application.biz.identity;

/** Inbound carrier for a login attempt. Built by the controller from the validated request DTO. */
public record LoginInfo(String email, String password) {
}
