package com.example.wallet.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** HTTP-запрос создания: валюта обязательна и ограничена RUB; правила существования проверяет Wallet. */
public record CreateWalletRequestDto(@NotNull @Pattern(regexp = "RUB") String currency) {
}
