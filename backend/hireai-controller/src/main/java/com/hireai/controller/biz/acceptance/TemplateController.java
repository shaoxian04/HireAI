package com.hireai.controller.biz.acceptance;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.acceptance.dto.TemplateDTO;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The platform's published Templates (ADR 0009). Read-only and public to every signed-in role and
 * API key, like the rest of {@code /api/catalogue/**}: Clients read what a Contract means, Builders
 * what an Offer can accept.
 */
@RestController
@RequestMapping("/api/catalogue/templates")
public class TemplateController extends BaseController {

    private final TemplateReadAppService templateReadAppService;

    public TemplateController(TemplateReadAppService templateReadAppService) {
        this.templateReadAppService = templateReadAppService;
    }

    @GetMapping
    public WebResult<List<TemplateDTO>> list() {
        return ok(templateReadAppService.list().stream().map(Acceptance2DTOConverter::toDTO).toList());
    }

    @GetMapping("/{key}/{version}")
    public WebResult<TemplateDTO> get(@PathVariable("key") String key, @PathVariable("version") int version) {
        return ok(Acceptance2DTOConverter.toDTO(templateReadAppService.get(new TemplateRef(key, version))));
    }
}
