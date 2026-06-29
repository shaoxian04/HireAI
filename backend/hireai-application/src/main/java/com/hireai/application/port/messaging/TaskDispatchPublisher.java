package com.hireai.application.port.messaging;

import com.hireai.domain.biz.task.routing.info.DispatchMessage;

/**
 * Application port for publishing a task dispatch onto the messaging fabric. The
 * routing orchestration (Plan 4) depends on this interface only — it never imports the
 * RabbitMQ adapter. Plan 2 provides the implementation in
 * {@code infrastructure/messaging}, publishing to the {@code task.dispatch} exchange.
 */
public interface TaskDispatchPublisher {

    void publish(DispatchMessage message);
}
