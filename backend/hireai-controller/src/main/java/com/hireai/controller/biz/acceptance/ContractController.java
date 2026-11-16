package com.hireai.controller.biz.acceptance;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.extraction.ContractDraftAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.acceptance.dto.ContractDraftDTO;
import com.hireai.controller.biz.acceptance.dto.ContractDraftRequest;
import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Contract drafts (spec S6, D28 Q28b): {@code POST /api/contracts/draft} returns the Contract the
 * platform would extract from a description, without creating a Task or freezing anything. A Client
 * can edit it and submit it as its own Contract (mode 2). Reachable by a human CLIENT or an API key.
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController extends BaseController {

    private final ContractDraftAppService contractDraftAppService;
    private final TemplateReadAppService templateReadAppService;

    public ContractController(ContractDraftAppService contractDraftAppService,
                              TemplateReadAppService templateReadAppService) {
        this.contractDraftAppService = contractDraftAppService;
        this.templateReadAppService = templateReadAppService;
    }

    @PostMapping("/draft")
    public WebResult<ContractDraftDTO> draft(@Valid @RequestBody ContractDraftRequest request) {
        SubmitTaskRequest.OutputSpecRequest spec = request.outputSpec();
        boolean namesTemplate = request.template() != null && !request.template().isBlank();
        if (namesTemplate && spec != null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Give template or outputSpec, not both");
        }
        Contract base = namesTemplate
                ? new Contract(templateReadAppService.current(request.template().strip()).ref(), Map.of())
                : (spec != null ? new OutputSpec(spec.format(), spec.schema(), spec.acceptanceCriteria())
                        : new OutputSpec(OutputFormat.TEXT, null, null)).toContract();
        return ok(Acceptance2DTOConverter.toDTO(contractDraftAppService.draft(request.title(), request.description(),
                spec == null ? null : spec.acceptanceCriteria(), base)));
    }
}
