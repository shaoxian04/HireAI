package com.hireai.controller.biz.catalogue;

import com.hireai.application.biz.offering.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.catalogue.dto.AgentCardDTO;
import com.hireai.controller.biz.catalogue.dto.AgentProfileDTO;
import com.hireai.controller.biz.catalogue.dto.CategoryCountDTO;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public catalogue surface (Module 6). Authenticated like everything else, but NOT owner-scoped:
 * any signed-in user can browse. Only ACTIVE + listed agents are reachable; owner-private fields
 * (webhook URL, owner id) are deliberately absent from the DTOs (spec §6).
 */
@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController extends BaseController {

    private final CatalogueReadAppService readAppService;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public CatalogueController(CatalogueReadAppService readAppService,
                               @Qualifier("outputSpecJsonMapper") OutputSpecJsonMapper outputSpecJsonMapper) {
        this.readAppService = readAppService;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @GetMapping("/agents")
    public WebResult<List<AgentCardDTO>> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AgentCardDTO> cards = readAppService.search(q, category, sort, page, size)
                .stream().map(CatalogueController::toCardDTO).toList();
        return ok(cards);
    }

    @GetMapping("/agents/{agentId}")
    public WebResult<AgentProfileDTO> profile(@PathVariable("agentId") UUID agentId) {
        AgentProfileRow row = readAppService.getProfile(agentId);
        OutputSpec spec = outputSpecJsonMapper.fromJson(row.outputSpecJson());
        int requests = row.card().requestCount();
        Double successRate = requests == 0 ? null : (double) row.completedCount() / requests;
        AgentProfileDTO dto = new AgentProfileDTO(
                toCardDTO(row.card()),
                row.description(),
                row.sampleOutput(),
                row.galleryUrls(),
                new AgentProfileDTO.OutputSpecDTO(
                        spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                new AgentProfileDTO.StatsDTO(
                        requests, row.completedCount(), successRate, row.avgTurnaroundSeconds()),
                readAppService.reviews(agentId).stream()
                        .map(r -> new AgentProfileDTO.ReviewDTO(
                                r.id(), r.rating(), r.reviewText(),
                                r.builderResponse(), r.author(), r.createdAt()))
                        .toList());
        return ok(dto);
    }

    @GetMapping("/categories")
    public WebResult<List<CategoryCountDTO>> categories() {
        return ok(readAppService.categories().stream()
                .map(c -> new CategoryCountDTO(c.category(), c.agentCount()))
                .toList());
    }

    private static AgentCardDTO toCardDTO(AgentCardRow c) {
        return new AgentCardDTO(
                c.id(), c.name(), c.builderName(), c.tagline(), c.logoUrl(), c.coverUrl(),
                c.categories(), c.price(), c.maxExecutionSeconds(),
                c.reputationScore(), c.ratingAvg(), c.ratingCount(), c.requestCount(),
                c.featured(), c.createdAt());
    }
}
