package com.example.wallet.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * HTTP-запрос пополнения/списания: сумма в копейках и версия обязательны и положительны.
 * Long сохраняет различие между отсутствующим полем и нулём; дробные числа отвергает Jackson.
 */
public record MoneyRequestDto(
        @NotNull @Positive Long amountMinor,
        @NotNull @Positive Long expectedVersion
) {
}
