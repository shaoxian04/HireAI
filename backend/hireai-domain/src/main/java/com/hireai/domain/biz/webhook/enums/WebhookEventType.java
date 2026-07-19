package com.hireai.domain.biz.webhook.enums;

public enum WebhookEventType {
    TASK_COMPLETED("task.completed"), TASK_FAILED("task.failed");
    private final String wire;
    WebhookEventType(String wire) { this.wire = wire; }
    public String wire() { return wire; }
}
