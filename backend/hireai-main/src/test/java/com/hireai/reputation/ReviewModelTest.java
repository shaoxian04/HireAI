package com.hireai.reputation;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.reputation.model.ReviewModel;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast domain unit tests — no Spring context, no DB.
 */
class ReviewModelTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void rejectsRatingZero() {
        assertThatThrownBy(() -> ReviewModel.forTask(TASK_ID, CLIENT_ID, AGENT_ID, 0, "text"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsRatingSix() {
        assertThatThrownBy(() -> ReviewModel.forTask(TASK_ID, CLIENT_ID, AGENT_ID, 6, "text"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    /**
     * A review detached from a task is no longer representable. The fabricated V7 seeds were
     * exactly that shape, and purging them is only durable if the model refuses to make more.
     */
    @Test
    void refusesAReviewThatNamesNoTask() {
        assertThatThrownBy(() -> ReviewModel.forTask(null, CLIENT_ID, AGENT_ID, 5, "text"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("task");
    }

    @Test
    void respondRejectsBlank() {
        ReviewModel review = ReviewModel.forTask(TASK_ID, CLIENT_ID, AGENT_ID, 5, "great");
        assertThatThrownBy(() -> review.respond("   "))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void respondTrimsAndPreservesOtherFields() {
        ReviewModel review = ReviewModel.forTask(TASK_ID, CLIENT_ID, AGENT_ID, 4, "nice work");
        ReviewModel responded = review.respond("  Thanks — glad it helped!  ");

        assertThat(responded.builderResponse()).isEqualTo("Thanks — glad it helped!");
        assertThat(responded.id()).isEqualTo(review.id());
        assertThat(responded.taskId()).isEqualTo(TASK_ID);
        assertThat(responded.clientId()).isEqualTo(CLIENT_ID);
        assertThat(responded.agentId()).isEqualTo(AGENT_ID);
        assertThat(responded.rating()).isEqualTo(4);
        assertThat(responded.reviewText()).isEqualTo("nice work");
        assertThat(responded.published()).isTrue();
        assertThat(responded.createdAt()).isEqualTo(review.createdAt());
    }
}
