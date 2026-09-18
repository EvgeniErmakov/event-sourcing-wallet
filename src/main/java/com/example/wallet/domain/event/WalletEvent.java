package com.example.wallet.domain.event;

/** Неизменяемые бизнес-факты без инфраструктурного envelope и Jackson-аннотаций. */
public sealed interface WalletEvent {
    /** Факт создания с зафиксированной валютой. */
    record WalletCreated(String currency) implements WalletEvent {
    }

    /** Факт увеличения остатка в копейках. */
    record MoneyDeposited(long amountMinor) implements WalletEvent {
    }

    /** Факт уменьшения остатка в копейках. */
    record MoneyWithdrawn(long amountMinor) implements WalletEvent {
    }

    /** Факт закрытия; payload в хранилище — пустой объект. */
    record WalletClosed() implements WalletEvent {
    }
}
