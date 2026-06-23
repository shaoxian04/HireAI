package com.hireai.domain.biz.task.enums;

/** How the client resolved a RESULT_RECEIVED task. Null on the task until it is RESOLVED. */
public enum TaskResolution {
    ACCEPTED,
    REJECTED
}
