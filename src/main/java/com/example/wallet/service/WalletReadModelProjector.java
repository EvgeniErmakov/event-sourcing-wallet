package com.example.wallet.service;

import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.exception.infrastructure.ProjectionIntegrityException;
import com.example.wallet.repository.WalletReadModelRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Применяет уже принятые факты внутри отдельной транзакции фонового обработчика.
 * Не вызывает decide, не читает receipt, не генерирует событий и не открывает транзакций.
 * Ошибка порядка, ограничений или BIGINT-арифметики откатывает проекцию и позицию этой порции.
 */
@Component
public class WalletReadModelProjector {
    private final WalletReadModelRepository models;

    public WalletReadModelProjector(WalletReadModelRepository models) {
        this.models = models;
    }

    /**
     * Применяет версию 1 вставкой, остальные — условным UPDATE по предыдущей версии.
     * Проверки защищают целостность фактов, а не повторяют правила приёма бизнес-команд.
     * Повторное применение не игнорируется: это ошибка интеграции или повреждение проекции.
     */
    public void apply(UUID walletId, long eventVersion, WalletEvent event) {
        if (eventVersion < 1) {
            throw new ProjectionIntegrityException("Версия проекции должна быть положительной");
        }
        try {
            int updated = switch (event) {
                case WalletEvent.WalletCreated e -> {
                    if (eventVersion != 1 || !"RUB".equals(e.currency())) {
                        throw new ProjectionIntegrityException("Некорректный факт создания проекции");
                    }
                    models.insert(walletId, e.currency());
                    yield 1;
                }
                case WalletEvent.MoneyDeposited e ->
                        models.changeBalance(walletId, positiveAmount(e.amountMinor()), eventVersion);
                case WalletEvent.MoneyWithdrawn e ->
                        models.changeBalance(walletId, -positiveAmount(e.amountMinor()), eventVersion);
                case WalletEvent.WalletClosed ignored -> models.close(walletId, eventVersion);
            };
            if (updated != 1) {
                throw new ProjectionIntegrityException("Отсутствующая строка, неверное состояние или версия проекции: walletId="
                        + walletId + ", eventVersion=" + eventVersion);
            }
        } catch (DataIntegrityViolationException error) {
            throw new ProjectionIntegrityException("Нарушение ограничений или переполнение проекции: walletId="
                    + walletId + ", eventVersion=" + eventVersion, error);
        }
    }

    private static long positiveAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new ProjectionIntegrityException("Неположительная сумма сохранённого факта");
        }
        return amountMinor;
    }
}
