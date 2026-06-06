package com.hireai.controller.biz.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentStorefrontAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agent.converter.AgentModel2DTOConverter;
import com.hireai.application.port.query.BuilderStatsQueryPort;
import com.hireai.controller.biz.agent.dto.AgentDTO;
import com.hireai.controller.biz.agent.dto.AgentProfileViewDTO;
import com.hireai.controller.biz.agent.dto.AgentStatsDTO;
import com.hireai.controller.biz.agent.dto.RegisterAgentRequest;
import com.hireai.controller.biz.agent.dto.RespondReviewRequest;
import com.hireai.controller.biz.agent.dto.ReviewDTO;
import com.hireai.controller.biz.agent.dto.UpdatePricingRequest;
import com.hireai.controller.biz.agent.dto.UpdateProfileRequest;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.info.PricingUpdateInfo;
import com.hireai.domain.biz.agent.info.ProfileUpdateInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Agent HTTP surface. Thin: validate the request, resolve owner identity server-side, build
 * the domain carrier, call one app service, wrap the result. Owner identity comes from
 * {@link CurrentUserProvider} (the JWT principal) — never from path or body (Invariant #5).
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController extends BaseController {

    private final AgentWriteAppService writeAppService;
    private final AgentReadAppService readAppService;
    private final AgentStorefrontAppService storefrontAppService;
    private final CurrentUserProvider currentUser;

    public AgentController(AgentWriteAppService writeAppService,
                           AgentReadAppService readAppService,
                           AgentStorefrontAppService storefrontAppService,
                           CurrentUserProvider currentUser) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
        this.storefrontAppService = storefrontAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<AgentDTO> register(@Valid @RequestBody RegisterAgentRequest request) {
        UUID ownerId = currentUser.currentUserId();
        RegisterAgentRequest.OutputSpecRequest specRequest = request.outputSpec();
        AgentRegisterInfo info = new AgentRegisterInfo(
                ownerId,
                request.name(),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.capabilityCategories(),
                request.webhookUrl(),
                request.maxExecutionSeconds(),
                request.price());
        UUID agentId = writeAppService.register(info);
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @PostMapping("/{agentId}/activate")
    public WebResult<AgentDTO> activate(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        writeAppService.activate(agentId, ownerId);
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @PutMapping("/{agentId}/pricing")
    public WebResult<AgentDTO> updatePricing(@PathVariable("agentId") UUID agentId,
                                             @Valid @RequestBody UpdatePricingRequest request) {
        UUID ownerId = currentUser.currentUserId();
        AgentModel updated = writeAppService.updatePricing(agentId, ownerId,
                new PricingUpdateInfo(request.price(), request.maxExecutionSeconds(),
                        request.capabilityCategories()));
        return ok(AgentModel2DTOConverter.toDTO(updated));
    }

    @GetMapping("/{agentId}")
    public WebResult<AgentDTO> getById(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @GetMapping
    public WebResult<List<AgentDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID ownerId = currentUser.currentUserId();
        List<AgentDTO> agents = readAppService.listForOwner(ownerId, new AgentQuery(page, size))
                .stream()
                .map(AgentModel2DTOConverter::toDTO)
                .toList();
        return ok(agents);
    }

    // ---- Storefront management (builder-only, owner-gated) ----

    @GetMapping("/{agentId}/profile")
    public WebResult<AgentProfileViewDTO> getProfile(@PathVariable("agentId") UUID agentId) {
        return ok(AgentProfileViewDTO.from(
                storefrontAppService.getProfile(agentId, currentUser.currentUserId())));
    }

    @PutMapping("/{agentId}/profile")
    public WebResult<AgentProfileViewDTO> updateProfile(@PathVariable("agentId") UUID agentId,
                                                        @Valid @RequestBody UpdateProfileRequest request) {
        return ok(AgentProfileViewDTO.from(storefrontAppService.updateProfile(
                agentId, currentUser.currentUserId(),
                new ProfileUpdateInfo(request.tagline(), request.description(),
                        request.sampleOutput(), request.isListed()))));
    }

    @PostMapping(value = "/{agentId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WebResult<AgentProfileViewDTO> uploadMedia(@PathVariable("agentId") UUID agentId,
                                                      @RequestParam("kind") String kind,
                                                      @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Could not read upload");
        }
        return ok(AgentProfileViewDTO.from(storefrontAppService.uploadMedia(
                agentId, currentUser.currentUserId(), kind,
                file.getContentType() == null ? "" : file.getContentType(),
                file.getSize(), bytes)));
    }

    @DeleteMapping("/{agentId}/media")
    public WebResult<AgentProfileViewDTO> removeMedia(@PathVariable("agentId") UUID agentId,
                                                      @RequestParam("kind") String kind,
                                                      @RequestParam("url") String url) {
        return ok(AgentProfileViewDTO.from(storefrontAppService.removeMedia(
                agentId, currentUser.currentUserId(), kind, url)));
    }

    @GetMapping("/{agentId}/reviews")
    public WebResult<List<ReviewDTO>> reviews(@PathVariable("agentId") UUID agentId) {
        return ok(storefrontAppService.reviews(agentId, currentUser.currentUserId())
                .stream().map(ReviewDTO::from).toList());
    }

    @PutMapping("/{agentId}/reviews/{reviewId}/response")
    public WebResult<ReviewDTO> respond(@PathVariable("agentId") UUID agentId,
                                        @PathVariable("reviewId") UUID reviewId,
                                        @Valid @RequestBody RespondReviewRequest request) {
        return ok(ReviewDTO.from(storefrontAppService.respondToReview(
                agentId, currentUser.currentUserId(), reviewId, request.response())));
    }

    @GetMapping("/{agentId}/stats")
    public WebResult<AgentStatsDTO> stats(@PathVariable("agentId") UUID agentId) {
        BuilderStatsQueryPort.StatsBundle bundle =
                storefrontAppService.getStats(agentId, currentUser.currentUserId());
        BuilderStatsQueryPort.StatsRow s = bundle.stats();
        Double successRate = s.total() == 0 ? null : (double) s.completed() / s.total();
        Double onTimeRate = s.withResultCount() == 0 ? null
                : (double) s.onTimeCount() / s.withResultCount();
        return ok(new AgentStatsDTO(
                new AgentStatsDTO.Volume(s.total(), s.completed(), s.failed(), s.open(), successRate),
                new AgentStatsDTO.Performance(s.avgTurnaroundSeconds(), onTimeRate),
                new AgentStatsDTO.Earnings(s.creditsInEscrow(), s.potentialEarnings()),
                bundle.trend().stream()
                        .map(t -> new AgentStatsDTO.TrendPoint(t.day(), t.count()))
                        .toList(),
                bundle.recent().stream()
                        .map(r -> new AgentStatsDTO.RecentTask(r.id(), r.title(), r.status(), r.createdAt()))
                        .toList()));
    }
}
