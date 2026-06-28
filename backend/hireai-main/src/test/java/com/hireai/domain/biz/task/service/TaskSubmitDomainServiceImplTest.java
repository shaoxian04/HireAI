package com.hireai.domain.biz.task.service;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.service.impl.TaskSubmitDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSubmitDomainServiceImplTest {

    private final TaskSubmitDomainService service = new TaskSubmitDomainServiceImpl();

    @Test
    void submitBuildsSubmittedTaskCarryingCategory() {
        TaskSubmitInfo info = new TaskSubmitInfo(UUID.randomUUID(), "title", "desc",
                Money.of("10.00"), new OutputSpec(OutputFormat.TEXT, null, "summary"), "translation");

        TaskModel task = service.submit(info);

        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.category()).isEqualTo("translation");
    }
}
