package com.hireai.application.biz.catalogue;

import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.application.port.query.CatalogueQueryPort.CategoryCountRow;
import com.hireai.application.port.query.CatalogueQueryPort.ReviewRow;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Public catalogue reads. No ownership scoping — visibility is ACTIVE + listed, enforced below.
 * Any authenticated user may browse (spec §6); owner-private fields are absent from responses.
 */
@Validated
public interface CatalogueReadAppService {

    List<AgentCardRow> search(String q, String category, String sort, int page, int size);

    /**
     * @throws com.hireai.domain.shared.exception.DomainException NOT_FOUND when absent/unlisted.
     */
    AgentProfileRow getProfile(UUID agentId);

    List<CategoryCountRow> categories();

    List<ReviewRow> reviews(UUID agentId);
}
