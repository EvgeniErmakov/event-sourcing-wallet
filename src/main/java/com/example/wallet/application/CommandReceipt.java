package com.example.wallet.application;

import java.time.Instant;
import java.util.UUID;

/** Завершённый успешный ответ. GET не использует receipt для восстановления кошелька. */
public record CommandReceipt(UUID commandId, String requestFingerprint, int responseStatus,
                             WalletState responseBody, Instant completedAt) { }
