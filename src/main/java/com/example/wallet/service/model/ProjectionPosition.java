package com.example.wallet.service.model;

import java.util.UUID;

/**
 * Сохранённая позиция фонового обработчика одного потока.
 * Позиция не является источником истины: она только говорит, до какой версии
 * уже зафиксирована производная модель чтения.
 */
public record ProjectionPosition(UUID walletId, long lastProcessedVersion) {
}
