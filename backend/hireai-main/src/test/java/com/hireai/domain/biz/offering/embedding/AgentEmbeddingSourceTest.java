package com.hireai.domain.biz.offering.embedding;

import com.hireai.domain.biz.offering.embedding.info.AgentEmbeddingSource;
import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;
import com.hireai.domain.biz.offering.embedding.model.AgentProfileText;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEmbeddingSourceTest {

    private static final String MODEL = "text-embedding-3-small";
    // SHA-256 of "Invoice Bot\nReads invoices" (independently computed with sha256sum).
    private static final String INVOICE_BOT_HASH = "365e09e1bd980197475edeffcda134cfae4bf15f336101e186274f17ec48812b";

    @Test
    void profileTextJoinsNameTaglineAndDescriptionSkippingBlanks() {
        assertThat(AgentProfileText.of(" Invoice Bot ", "Reads invoices", "  ").text())
                .isEqualTo("Invoice Bot\nReads invoices");
        assertThat(AgentProfileText.of("Only Name", null, null).text()).isEqualTo("Only Name");
    }

    @Test
    void profileTextHashIsTheSha256HexOfTheText() {
        assertThat(AgentProfileText.of("Invoice Bot", "Reads invoices", null).hash()).isEqualTo(INVOICE_BOT_HASH);
    }

    @Test
    void aMissingVectorNeedsEmbedding() {
        assertThat(source(null, null).needsEmbedding(MODEL)).isTrue();
    }

    @Test
    void aCurrentVectorDoesNot() {
        assertThat(source(MODEL, INVOICE_BOT_HASH).needsEmbedding(MODEL)).isFalse();
    }

    @Test
    void aVectorFromOtherTextOrAnotherModelDoes() {
        assertThat(source(MODEL, "older-hash").needsEmbedding(MODEL)).isTrue();
        assertThat(source("text-embedding-ada-002", INVOICE_BOT_HASH).needsEmbedding(MODEL)).isTrue();
    }

    @Test
    void anEmbeddingMustHave1536Dimensions() {
        AgentProfileText text = AgentProfileText.of("A", null, null);
        assertThatThrownBy(() -> AgentEmbeddingModel.of(UUID.randomUUID(), MODEL, text, new float[3], Instant.now()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("1536");
        assertThat(AgentEmbeddingModel.of(UUID.randomUUID(), MODEL, text, new float[1536], Instant.now())
                .textHash()).isEqualTo(text.hash());
    }

    private static AgentEmbeddingSource source(String storedModel, String storedHash) {
        return new AgentEmbeddingSource(UUID.randomUUID(), "Invoice Bot", "Reads invoices", null,
                storedModel, storedHash);
    }
}
