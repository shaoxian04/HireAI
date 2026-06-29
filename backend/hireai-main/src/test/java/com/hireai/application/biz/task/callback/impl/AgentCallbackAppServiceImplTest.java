package com.hireai.application.biz.task.callback.impl;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCallbackAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock DispatchTokenService dispatchTokenService;

    private AgentCallbackAppServiceImpl service() {
        return new AgentCallbackAppServiceImpl(taskRepository, dispatchTokenService);
    }

    private TaskModel executingTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
    }

    private AgentResultInfo result() {
        return new AgentResultInfo("COMPLETED", "{\"k\":\"v\"}", "https://x/y", "done");
    }

    @Test
    void recordResultVerifiesTokenTransitionsAndSaves() {
        TaskModel task = executingTask();
        UUID agentVersionId = task.agentVersionId();
        when(dispatchTokenService.verify("good"))
                .thenReturn(new DispatchTokenClaims(task.id(), agentVersionId, Instant.now().plusSeconds(60)));
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().recordResult(task.id(), "good", result());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(captor.getValue().result()).isNotNull();
        assertThat(captor.getValue().result().agentStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().result().resultPayloadJson()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void recordResultPropagatesInvalidTokenAndNeverSaves() {
        UUID taskId = UUID.randomUUID();
        when(dispatchTokenService.verify("bad"))
                .thenThrow(new DispatchTokenInvalidException("bad signature"));

        assertThatThrownBy(() -> service().recordResult(taskId, "bad", result()))
                .isInstanceOf(DispatchTokenInvalidException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordResultRejectsTaskIdMismatchAndNeverSaves() {
        UUID pathTaskId = UUID.randomUUID();
        UUID tokenTaskId = UUID.randomUUID();
        when(dispatchTokenService.verify("mismatch"))
                .thenReturn(new DispatchTokenClaims(tokenTaskId, UUID.randomUUID(), Instant.now().plusSeconds(60)));

        assertThatThrownBy(() -> service().recordResult(pathTaskId, "mismatch", result()))
                .isInstanceOf(DispatchTokenInvalidException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordResultRejectsAgentVersionMismatchAndNeverSaves() {
        TaskModel task = executingTask();
        UUID otherVersionId = UUID.randomUUID();
        // Token is well-formed and its taskId matches the path, but it authorises a DIFFERENT
        // agent version than the one assigned to the loaded task -> must fail like a bad token.
        when(dispatchTokenService.verify("wrong-agent"))
                .thenReturn(new DispatchTokenClaims(task.id(), otherVersionId, Instant.now().plusSeconds(60)));
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service().recordResult(task.id(), "wrong-agent", result()))
                .isInstanceOf(DispatchTokenInvalidException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordResultThrowsNotFoundWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(dispatchTokenService.verify("good"))
                .thenReturn(new DispatchTokenClaims(taskId, UUID.randomUUID(), Instant.now().plusSeconds(60)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recordResult(taskId, "good", result()))
                .isInstanceOf(DomainException.class);
    }
}
