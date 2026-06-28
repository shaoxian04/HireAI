package com.hireai.controller.biz.wallet;

import com.hireai.application.biz.ledger.wallet.BuilderEarningsReadAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.wallet.dto.BuilderEarningsDTO;
import com.hireai.controller.config.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builder earnings HTTP surface. Identity comes from {@link CurrentUserProvider} only
 * (invariant #5) — no parameters to tamper with; a caller who owns no agents gets zeros.
 */
@RestController
@RequestMapping("/api/builder/earnings")
public class BuilderEarningsController extends BaseController {

    private final BuilderEarningsReadAppService earningsService;
    private final CurrentUserProvider currentUser;

    public BuilderEarningsController(BuilderEarningsReadAppService earningsService,
                                     CurrentUserProvider currentUser) {
        this.earningsService = earningsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public WebResult<BuilderEarningsDTO> earnings() {
        return ok(BuilderEarningsDTO.from(earningsService.earningsFor(currentUser.currentUserId())));
    }
}
