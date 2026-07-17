package com.hireai.controller.biz.apikey;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.apikey.dto.ApiKeyDTO;
import com.hireai.controller.biz.apikey.dto.CreateApiKeyRequest;
import com.hireai.controller.biz.apikey.dto.CreatedApiKeyDTO;
import com.hireai.controller.config.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API-key management surface. JWT-only (the security allow-list restricts /api/keys/** to ROLE_CLIENT —
 * an API key cannot mint keys). Identity comes from {@link CurrentUserProvider}; ownership is enforced
 * in the app service (Invariant #5). The raw key is returned exactly once, from POST.
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController extends BaseController {

    private final ApiKeyManagementAppService managementAppService;
    private final CurrentUserProvider currentUser;

    public ApiKeyController(ApiKeyManagementAppService managementAppService,
                            CurrentUserProvider currentUser) {
        this.managementAppService = managementAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<CreatedApiKeyDTO> create(@Valid @RequestBody CreateApiKeyRequest request) {
        UUID ownerId = currentUser.currentUserId();
        return ok(ApiKey2DTOConverter.toCreatedDTO(managementAppService.create(
                ownerId, request.name(), request.spendCap(), request.dailySpendCap())));
    }

    @GetMapping
    public WebResult<List<ApiKeyDTO>> list() {
        UUID ownerId = currentUser.currentUserId();
        return ok(managementAppService.list(ownerId).stream()
                .map(ApiKey2DTOConverter::toDTO).toList());
    }

    @PostMapping("/{id}/revoke")
    public WebResult<ApiKeyDTO> revoke(@PathVariable("id") UUID id) {
        UUID ownerId = currentUser.currentUserId();
        return ok(ApiKey2DTOConverter.toDTO(managementAppService.revoke(id, ownerId)));
    }
}
