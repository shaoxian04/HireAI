package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.TEXT, null, "concise summary");
    }

    @Test
    void submitBuildsSubmittedTask() {
        UUID clientId = UUID.randomUUID();
        TaskModel task = TaskModel.submit(clientId, "Summarise doc", "Summarise the attached report",
                Money.of("25.00"), spec());

        assertThat(task.id()).isNotNull();
        assertThat(task.clientId()).isEqualTo(clientId);
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.budget()).isEqualTo(Money.of("25.00"));
        assertThat(task.outputSpec()).isEqualTo(spec());
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void trimsTitleAndDescription() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "  title  ", "  desc  ", Money.of("5.00"), spec());
        assertThat(task.title()).isEqualTo("title");
        assertThat(task.description()).isEqualTo("desc");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "  ", "desc", Money.of("5.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "", Money.of("5.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("0.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("5.00"), null))
                .isInstanceOf(DomainException.class);
    }
}
