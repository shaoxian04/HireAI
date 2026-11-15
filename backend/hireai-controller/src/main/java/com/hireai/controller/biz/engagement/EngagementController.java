package com.hireai.controller.biz.engagement;

import com.hireai.application.biz.acceptance.engagement.EngagementAppService;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.acceptance.converter.ContractRequest2ModelConverter;
import com.hireai.controller.biz.engagement.dto.EngagementDTOs.EngagementDTO;
import com.hireai.controller.biz.engagement.dto.EngagementDTOs.OpenEngagementRequest;
import com.hireai.controller.biz.engagement.dto.EngagementDTOs.SourceDTO;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Engagements and their documents (spec S4, D44, ADR 0010). The caller is always the JWT (or API key)
 * principal (Hard Invariant #5): a buyer sees and changes only its own Engagements, a Builder accepts
 * only an Engagement on an Agent it owns; anything else is 404. Documents arrive by upload only.
 */
@RestController
@RequestMapping("/api/engagements")
public class EngagementController extends BaseController {

    private final EngagementAppService engagementAppService;
    private final SourceSnapshotAppService sourceSnapshotAppService;
    private final CurrentUserProvider currentUser;

    public EngagementController(EngagementAppService engagementAppService,
                                SourceSnapshotAppService sourceSnapshotAppService,
                                CurrentUserProvider currentUser) {
        this.engagementAppService = engagementAppService;
        this.sourceSnapshotAppService = sourceSnapshotAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<EngagementDTO> open(@Valid @RequestBody OpenEngagementRequest request) {
        return ok(EngagementDTO.of(engagementAppService.open(currentUser.currentUserId(), request.agentId(),
                request.confidentialConsent(), ContractRequest2ModelConverter.toModel(request.templateVariant()),
                request.retentionDays())));
    }

    /** Your Engagements as a buyer, newest first. */
    @GetMapping
    public WebResult<List<EngagementDTO>> mine() {
        return ok(engagementAppService.listForClient(currentUser.currentUserId()).stream()
                .map(EngagementDTO::of).toList());
    }

    /** Engagements on Agents you own, as a Builder, newest first. */
    @GetMapping("/incoming")
    public WebResult<List<EngagementDTO>> incoming() {
        return ok(engagementAppService.listForBuilder(currentUser.currentUserId()).stream()
                .map(EngagementDTO::of).toList());
    }

    @GetMapping("/{id}")
    public WebResult<EngagementDTO> get(@PathVariable("id") UUID id) {
        return ok(EngagementDTO.of(engagementAppService.getForParticipant(id, currentUser.currentUserId())));
    }

    /** The Builder of the Engagement's Agent accepts its Template variant. */
    @PostMapping("/{id}/accept")
    public WebResult<EngagementDTO> accept(@PathVariable("id") UUID id) {
        return ok(EngagementDTO.of(engagementAppService.acceptVariant(id, currentUser.currentUserId())));
    }

    @PostMapping("/{id}/end")
    public WebResult<EngagementDTO> end(@PathVariable("id") UUID id) {
        return ok(EngagementDTO.of(engagementAppService.end(id, currentUser.currentUserId())));
    }

    /** Uploads one document (multipart field {@code file}) into private storage, frozen as a SourceSnapshot. */
    @PostMapping(value = "/{id}/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WebResult<SourceDTO> upload(@PathVariable("id") UUID id, @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Could not read upload");
        }
        return ok(SourceDTO.of(sourceSnapshotAppService.upload(id, currentUser.currentUserId(),
                file.getOriginalFilename(), file.getContentType(), bytes), List.of()));
    }

    /** The Engagement's documents with their download logs (buyer only). */
    @GetMapping("/{id}/sources")
    public WebResult<List<SourceDTO>> sources(@PathVariable("id") UUID id) {
        return ok(sourceSnapshotAppService.listForEngagement(id, currentUser.currentUserId()).stream()
                .map(SourceDTO::of).toList());
    }
}
