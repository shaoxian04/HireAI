package com.hireai.domain.biz.offering.category.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryListTest {

    private static final CategoryList LIST = new CategoryList(List.of(
            new Category("document-extraction", "Document extraction"),
            new Category("summarisation", "Summarisation"),
            new Category("other", "Other")));

    @Test
    void aKnownSlugIsAcceptedInAnyCaseAndSurroundingSpace() {
        assertThat(LIST.requireKnown("  Summarisation ")).isEqualTo("summarisation");
    }

    @Test
    void anUnknownSlugIsAValidationErrorListingEveryValidSlug() {
        assertThatThrownBy(() -> LIST.requireKnown("summarization"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode()).isEqualTo(ResultCode.VALIDATION_ERROR))
                .hasMessage("Unknown category 'summarization'. Valid categories: "
                        + "document-extraction, summarisation, other");
    }

    @Test
    void aBlankCategoryIsRejected() {
        assertThatThrownBy(() -> LIST.requireKnown("  "))
                .isInstanceOf(DomainException.class)
                .hasMessageStartingWith("Unknown category ''. Valid categories:");
        assertThatThrownBy(() -> LIST.requireKnown(null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void everyTagMustBeKnownAndTheNormalisedListComesBack() {
        assertThat(LIST.requireAllKnown(List.of("Other", "document-extraction")))
                .containsExactly("other", "document-extraction");
        assertThatThrownBy(() -> LIST.requireAllKnown(List.of("other", "poetry")))
                .isInstanceOf(DomainException.class)
                .hasMessageStartingWith("Unknown category 'poetry'.");
    }
}
