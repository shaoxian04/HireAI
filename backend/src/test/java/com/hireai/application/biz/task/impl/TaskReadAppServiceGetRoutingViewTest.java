package com.hireai.application.biz.task.impl;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
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

    private TaskReadAppServiceImpl service() {
        return new TaskReadAppServiceImpl(taskRepository);
    }

    @Test
    void getRoutingViewReturnsCategoryBudgetAndStatus() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("42.50"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "translation");
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        TaskRoutingView view = service().getRoutingView(task.id());

        assertThat(view.taskId()).isEqualTo(task.id());
        assertThat(view.category()).isEqualTo("translation");
        assertThat(view.budget()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(view.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void getRoutingViewThrowsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRoutingView(taskId))
                .isInstanceOf(DomainException.class);
    }
}
