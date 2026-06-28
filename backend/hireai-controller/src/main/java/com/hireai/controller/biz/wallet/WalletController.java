package com.hireai.controller.biz.wallet;

import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.wallet.converter.WalletModel2DTOConverter;
import com.hireai.controller.biz.wallet.dto.LedgerEntryDTO;
import com.hireai.controller.biz.wallet.dto.TopUpRequest;
import com.hireai.controller.biz.wallet.dto.WalletDTO;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.ledger.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.shared.model.Money;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Wallet HTTP surface. Thin: validate the request, resolve identity server-side,
 * call one app service, wrap the result. Identity comes from {@link CurrentUserProvider}
 * (the JWT principal once auth lands) — never from a path or body.
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController extends BaseController {

    private final WalletWriteAppService writeAppService;
    private final WalletReadAppService readAppService;
    private final CurrentUserProvider currentUser;

    public WalletController(WalletWriteAppService writeAppService,
                            WalletReadAppService readAppService,
                            CurrentUserProvider currentUser) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public WebResult<WalletDTO> getWallet() {
        UUID userId = currentUser.currentUserId();
        WalletDTO dto = WalletModel2DTOConverter.toDTO(readAppService.getByUserId(userId));
        return ok(dto);
    }

    @PostMapping("/topup")
    public WebResult<WalletDTO> topUp(@Valid @RequestBody TopUpRequest request) {
        UUID userId = currentUser.currentUserId();
        String correlationId = UUID.randomUUID().toString();
        writeAppService.topUp(userId, Money.of(request.amount()), correlationId);
        WalletDTO dto = WalletModel2DTOConverter.toDTO(readAppService.getByUserId(userId));
        return ok(dto);
    }

    @GetMapping("/transactions")
    public WebResult<List<LedgerEntryDTO>> transactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID userId = currentUser.currentUserId();
        List<LedgerEntryDTO> entries = readAppService
                .getLedger(userId, new WalletLedgerQuery(page, size))
                .stream()
                .map(WalletModel2DTOConverter::toDTO)
                .toList();
        return ok(entries);
    }
}
