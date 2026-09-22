package com.example.wallet.domain.command;

import java.util.UUID;
import org.axonframework.modelling.annotation.TargetEntityId;

/** Локальная команда Axon сохраняет исходный глобальный ключ HTTP и fingerprint. */
public record DispatchWalletCommand(@TargetEntityId UUID walletId, UUID commandId,
        String fingerprint, WalletCommand command) { }
