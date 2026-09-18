package com.example.wallet.service.model;

import com.example.wallet.domain.Wallet;
import java.util.UUID;

/** Неизменяемый результат replay/команды; это ответ, а не отдельная модель хранения баланса. */
public record WalletState(
        UUID walletId,
        long balanceMinor,
        String currency,
        Wallet.Status status,
        long version
) {
    /** Снимает неизменяемый результат с восстановленного Wallet, не сохраняя отдельный баланс. */
    public static WalletState from(Wallet wallet) {
        return new WalletState(wallet.walletId(), wallet.balanceMinor(), wallet.currency(),
                wallet.status(), wallet.version());
    }
}
