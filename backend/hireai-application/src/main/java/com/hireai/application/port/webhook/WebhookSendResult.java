package com.hireai.application.port.webhook;

public record WebhookSendResult(boolean success, int statusCode, String error) {
    public static WebhookSendResult ok(int status) {
        return new WebhookSendResult(true, status, null);
    }

    public static WebhookSendResult fail(int status, String error) {
        return new WebhookSendResult(false, status, error);
    }
}
