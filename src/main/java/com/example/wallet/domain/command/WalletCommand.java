package com.example.wallet.domain.command;

/** Неизменяемые намерения клиента. Валидация команды не применяется при replay фактов. */
public sealed interface WalletCommand {
    /** Возвращает клиентскую версию без побочных эффектов; создание всегда ожидает пустой поток. */
    default long expectedVersion() {
        return switch (this) {
            case CreateWallet ignored -> 0;
            case DepositMoney c -> c.expectedVersion();
            case WithdrawMoney c -> c.expectedVersion();
            case CloseWallet c -> c.expectedVersion();
        };
    }

    /** Создание всегда ожидает пустой поток, то есть версию 0. */
    record CreateWallet(String currency) implements WalletCommand {
    }

    /** Пополнение на положительное число копеек. */
    record DepositMoney(
            long amountMinor,
            long expectedVersion
    ) implements WalletCommand {
    }

    /** Списание на положительное число копеек без автоматического повтора при конфликте. */
    record WithdrawMoney(
            long amountMinor,
            long expectedVersion
    ) implements WalletCommand {
    }

    /** Закрытие кошелька с нулевым остатком. */
    record CloseWallet(long expectedVersion) implements WalletCommand {
    }
}
