package com.example.wallet.service;

import com.example.wallet.service.model.EventPage;
import com.example.wallet.service.model.WalletComparison;
import com.example.wallet.service.model.WalletState;
import java.util.UUID;

/** Сторона чтения CQRS; не принимает команд и не исправляет отсутствующую проекцию при GET. */
public interface WalletQueryService {
    /** null atVersion — проекция; положительная версия — исторический replay. Неизвестный кошелёк даёт 404. */
    WalletState get(UUID walletId, Long atVersion);

    /** Страница неизменяемых фактов; limit 1–500, afterVersion >= 0. Не ограничивает полный replay. */
    EventPage history(UUID walletId, long afterVersion, int limit);

    /** Сверяет проекцию с префиксом истории той же бизнес-версии; общего снимка JPA/JDBC нет. */
    WalletComparison compare(UUID walletId);
}
