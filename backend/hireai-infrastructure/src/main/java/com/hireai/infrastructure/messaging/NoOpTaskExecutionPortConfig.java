package com.hireai.infrastructure.messaging;

import com.hireai.application.port.task.TaskExecutionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Fallback {@link TaskExecutionPort} so the dispatch pipeline ({@code TaskDispatchConsumer})
 * can wire and the application context can start in worktrees/builds where Plan 3's real
 * impl (over {@code TaskWriteAppService}) is not present. It is {@link ConditionalOnMissingBean},
 * so the moment any other {@code TaskExecutionPort} bean exists — Plan 3's production adapter,
 * or a test's {@code @Primary} stub — this no-op backs off entirely.
 *
 * <p>This preserves Plan 2's contracts-first isolation: the track depends only on the port,
 * never on Plan 3's concrete classes, yet every full-context test (including the pre-existing
 * Task/Wallet integration tests, which provide no execution-port impl) can still boot.
 */
@Configuration
@Slf4j
public class NoOpTaskExecutionPortConfig {

    @Bean
    @ConditionalOnMissingBean(TaskExecutionPort.class)
    public TaskExecutionPort noOpTaskExecutionPort() {
        log.warn("No TaskExecutionPort implementation found; using no-op fallback. "
                + "Dispatch will not transition task status until Plan 3's adapter is on the classpath.");
        return new TaskExecutionPort() {
            @Override
            public void markExecuting(UUID taskId) {
                log.debug("No-op markExecuting for task {}", taskId);
            }

            @Override
            public void markTimedOut(UUID taskId) {
                log.debug("No-op markTimedOut for task {}", taskId);
            }

            @Override
            public void markFailed(UUID taskId) {
                log.debug("No-op markFailed for task {}", taskId);
            }
        };
    }
}
