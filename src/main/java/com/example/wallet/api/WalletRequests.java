package com.example.wallet.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Входные DTO: boxed Long позволяет отличить отсутствующее значение от нуля. */
public final class WalletRequests {
    private WalletRequests() { }

    /** Создание принимает только явную валюту RUB. */
    public record Create(@NotNull @Pattern(regexp = "RUB") String currency) { }

    /** Сумма и ожидаемая версия обязательны, положительны и помещаются в long. */
    public record Money(@NotNull @Positive Long amountMinor, @NotNull @Positive Long expectedVersion) { }

    /** Закрытие требует версию, прочитанную клиентом. */
    public record Close(@NotNull @Positive Long expectedVersion) { }
}
