package com.hireai.domain.biz.task.info;

import java.time.Instant;

/**
 * A task's review window as stored: when it closes, and whether the platform accepted the result on
 * the Client's behalf because the window closed unreviewed (#94).
 */
public record ReviewWindowState(Instant deadline, boolean autoAccepted) {
}
