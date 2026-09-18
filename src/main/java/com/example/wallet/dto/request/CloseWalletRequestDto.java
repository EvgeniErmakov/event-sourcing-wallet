package com.example.wallet.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** HTTP-запрос закрытия с прочитанной клиентом версией; нулевой баланс проверяет домен. */
public record CloseWalletRequestDto(@NotNull @Positive Long expectedVersion) {
}
