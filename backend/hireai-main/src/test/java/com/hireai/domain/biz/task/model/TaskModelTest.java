package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
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
                Money.of("25.00"), spec(), "general");

        assertThat(task.id()).isNotNull();
        assertThat(task.clientId()).isEqualTo(clientId);
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.budget()).isEqualTo(Money.of("25.00"));
        assertThat(task.outputSpec()).isEqualTo(spec());
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void submitCarriesCategoryAndNullRoutingFields() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "Summarise doc", "Summarise the report",
                Money.of("25.00"), spec(), "summarisation");

        assertThat(task.category()).isEqualTo("summarisation");
        assertThat(task.agentVersionId()).isNull();
        assertThat(task.result()).isNull();
    }

    @Test
    void submitLowercasesCategory() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "Translate doc", "Translate the report",
                Money.of("25.00"), spec(), "Translation");

        assertThat(task.category()).isEqualTo("translation");
    }

    @Test
    void submitRejectsBlankCategory() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc",
                Money.of("5.00"), spec(), "  "))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void trimsTitleAndDescription() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "  title  ", "  desc  ", Money.of("5.00"), spec(), "general");
        assertThat(task.title()).isEqualTo("title");
        assertThat(task.description()).isEqualTo("desc");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "  ", "desc", Money.of("5.00"), spec(), "general"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "", Money.of("5.00"), spec(), "general"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("0.00"), spec(), "general"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("5.00"), null, "general"))
                .isInstanceOf(DomainException.class);
    }
}
