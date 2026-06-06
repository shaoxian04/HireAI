package com.hireai.application.biz.task.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import com.hireai.infrastructure.repository.task.OutputSpecJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReadAppServiceGetRoutingViewTest {

    @Mock TaskRepository taskRepository;

    private final OutputSpecJsonMapper outputSpecJsonMapper = new OutputSpecJsonMapper(new ObjectMapper());

    private TaskReadAppServiceImpl service() {
        return new TaskReadAppServiceImpl(taskRepository, outputSpecJsonMapper);
    }

    @Test
    void getRoutingViewReturnsCategoryBudgetStatusAndOutputSpec() {
        OutputSpec spec = new OutputSpec(OutputFormat.TEXT, null, "summary");
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("42.50"),
                spec, "translation");
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        TaskRoutingView view = service().getRoutingView(task.id());

        assertThat(view.taskId()).isEqualTo(task.id());
        assertThat(view.category()).isEqualTo("translation");
        assertThat(view.budget()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(view.status()).isEqualTo("SUBMITTED");
        // Invariant #4: the view carries the task's serialised output_spec snapshot.
        assertThat(view.outputSpecJson()).isNotBlank();
        assertThat(view.outputSpecJson()).contains("TEXT");
    }

    @Test
    void getRoutingViewThrowsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRoutingView(taskId))
                .isInstanceOf(DomainException.class);
    }
}
