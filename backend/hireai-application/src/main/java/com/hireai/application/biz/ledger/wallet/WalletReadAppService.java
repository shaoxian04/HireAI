package com.hireai.application.biz.ledger.wallet;

import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletLedgerQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates wallet READ use cases. Returns domain models to the controller,
 * which converts them to DTOs. Read-only transactions; cache-safe.
 */
@Validated
public interface WalletReadAppService {

    WalletModel getByUserId(@NonNull UUID userId);

    List<LedgerEntryModel> getLedger(@NonNull UUID userId, @NonNull WalletLedgerQuery query);
}
