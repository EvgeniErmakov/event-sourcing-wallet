package com.example.wallet.domain;

/** Отказ бизнес-операции с устойчивым кодом; транспортный статус назначает API. */
public final class WalletException extends RuntimeException {
    /** Конечный набор ожидаемых отказов, независимый от Spring и HTTP. */
    public enum Code {
        INVALID_REQUEST, WALLET_NOT_FOUND, VERSION_NOT_FOUND, WALLET_ALREADY_EXISTS,
        VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED, WALLET_CLOSED, INSUFFICIENT_FUNDS,
        NON_ZERO_BALANCE, BALANCE_OVERFLOW
    }

    private final Code code;

    public WalletException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
