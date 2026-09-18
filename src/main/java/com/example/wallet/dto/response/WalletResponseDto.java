package com.example.wallet.dto.response;

import com.example.wallet.domain.Wallet;
import com.example.wallet.service.model.WalletState;
import java.util.UUID;

/** HTTP-представление состояния с прежними JSON-ключами; не участвует в хранении receipt или replay. */
public record WalletResponseDto(
        UUID walletId,
        long balanceMinor,
        String currency,
        Wallet.Status status,
        long version
) {
    /** Переносит готовый результат сервиса в HTTP-контракт без повторного вычисления баланса. */
    public static WalletResponseDto from(WalletState state) {
        return new WalletResponseDto(state.walletId(), state.balanceMinor(), state.currency(), state.status(), state.version());
    }
}
