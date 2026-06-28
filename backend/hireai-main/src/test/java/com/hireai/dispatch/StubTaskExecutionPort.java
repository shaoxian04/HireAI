package com.hireai.dispatch;

import com.hireai.application.port.task.TaskExecutionPort;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-test implementation of the Plan 0 {@link TaskExecutionPort}. Plan 3's concrete impl is not
 * in this worktree, so the dispatch consumer is wired against this recorder. {@code @Primary}
 * ensures it wins over any other candidate on the test classpath.
 */
@TestComponent
@Primary
public class StubTaskExecutionPort implements TaskExecutionPort {

    public final CopyOnWriteArrayList<UUID> executing = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<UUID> timedOut = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<UUID> failed = new CopyOnWriteArrayList<>();

    @Override
    public void markExecuting(UUID taskId) {
        executing.add(taskId);
    }

    @Override
    public void markTimedOut(UUID taskId) {
        timedOut.add(taskId);
    }

    @Override
    public void markFailed(UUID taskId) {
        failed.add(taskId);
    }
}
