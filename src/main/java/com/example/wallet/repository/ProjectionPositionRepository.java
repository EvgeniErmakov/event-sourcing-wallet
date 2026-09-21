package com.example.wallet.repository;

import com.example.wallet.service.model.ProjectionPosition;
import java.util.List;
import java.util.UUID;

/** JDBC-граница сохранённого курсора асинхронной проекции. */
public interface ProjectionPositionRepository {
    /** Создаёт нулевую позицию в транзакции создания потока. */
    void create(UUID walletId);

    /** Возвращает все потоки; ограничение порции применяется к событиям каждого кошелька. */
    List<UUID> findWalletIds();

    /** Блокирует позицию конкретного кошелька до завершения транзакции обработчика. */
    ProjectionPosition lock(UUID walletId);

    /** Сдвигает позицию только на следующую непрерывно применённую версию. */
    void update(UUID walletId, long expectedPreviousVersion, long newVersion);
}
