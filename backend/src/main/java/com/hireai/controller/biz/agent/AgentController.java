package com.hireai.controller.biz.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agent.converter.AgentModel2DTOConverter;
import com.hireai.controller.biz.agent.dto.AgentDTO;
import com.hireai.controller.biz.agent.dto.RegisterAgentRequest;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.task.model.OutputSpec;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final CurrentUserProvider currentUser;

    public AgentController(AgentWriteAppService writeAppService,
                           AgentReadAppService readAppService,
                           CurrentUserProvider currentUser) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
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
}
