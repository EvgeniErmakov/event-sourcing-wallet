package com.example.wallet.service.model;

import com.example.wallet.domain.Wallet;
import java.util.UUID;

/** Производное состояние для чтения. Восстанавливается проектором; не используется для решений Wallet. */
public record WalletReadModel(UUID walletId, long balanceMinor, String currency,
        Wallet.Status status, long lastEventVersion) {
    /** Сохраняет прежний HTTP-контракт: version соответствует последнему применённому событию. */
    public WalletState toState() {
        return new WalletState(walletId, balanceMinor, currency, status, lastEventVersion);
    }
}
