package com.hireai.application.biz.wallet;

import com.hireai.domain.shared.model.Money;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates wallet WRITE use cases. Transactional; invokes per-transition
 * domain services and persists through the repository INTERFACE. Returns only the
 * aggregate ID — callers re-read full state via {@link WalletReadAppService}.
 */
@Validated
public interface WalletWriteAppService {

    UUID topUp(@NonNull UUID userId, @NonNull Money amount, @NonNull String correlationId);

    UUID freeze(@NonNull UUID userId, @NonNull Money amount, @NonNull UUID taskId, @NonNull String correlationId);
}
