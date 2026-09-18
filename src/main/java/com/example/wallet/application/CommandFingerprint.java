package com.example.wallet.application;

import com.example.wallet.domain.WalletCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 канонической команды, независимый от JSON, пробелов и порядка полей.
 * Типы названы явно, UUID нормализован, числа записаны десятичными, RUB — единственная валюта.
 * expectedVersion входит в содержание: её изменение требует нового ключа.
 */
public final class CommandFingerprint {
    private CommandFingerprint() { }

    public static String of(UUID walletId, WalletCommand command) {
        String fields = switch (command) {
            case WalletCommand.CreateWallet c -> "CreateWallet\n" + c.currency() + "\n0";
            case WalletCommand.DepositMoney c -> "DepositMoney\n" + c.amountMinor() + "\n" + c.expectedVersion();
            case WalletCommand.WithdrawMoney c -> "WithdrawMoney\n" + c.amountMinor() + "\n" + c.expectedVersion();
            case WalletCommand.CloseWallet c -> "CloseWallet\n" + c.expectedVersion();
        };
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((walletId + "\n" + fields).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK не предоставляет SHA-256", e);
        }
    }
}
