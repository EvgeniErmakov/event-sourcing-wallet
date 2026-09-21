package com.example.wallet.repository;

import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.service.model.StoredEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Граница прикладного сервиса и JDBC-хранилища: события — единственный источник состояния. */
public interface EventStore {
    /**
     * Один снимок полного потока, ASC по версии, без пагинации. Пусто — поток отсутствует.
     * Проверяет непрерывность версий перед replay; не создаёт и не изменяет событий.
     */
    List<StoredEvent> load(UUID walletId);

    /**
     * Добавляет один НОВЫЙ факт по expectedVersion внутри транзакции вызывающего сервиса.
     * При 0 создаёт поток; CAS, событие, позиция и receipt должны откатиться вместе.
     * Никогда не вызывается при replay. При гонке выбрасывает VERSION_CONFLICT или WALLET_ALREADY_EXISTS.
     */
    void append(UUID walletId, long expectedVersion, WalletEvent event, UUID eventId,
            UUID commandId, Instant occurredAt);

    /** Проверяет наличие истории, не обращаясь к receipt или отдельному балансу. */
    boolean exists(UUID walletId);

    /** Проверяет именно поток, включая повреждённый пустой: отсутствие проекции не маскируется под 404. */
    boolean streamExists(UUID walletId);

    /** Читает не более limit фактов после версии; сервис запрашивает limit+1 для hasMore. */
    List<StoredEvent> readPage(UUID walletId, long afterVersion, int limit);

    /** Читает ограниченную порцию следующих фактов для одной транзакции обработчика. */
    List<StoredEvent> readAfter(UUID walletId, long afterVersion, int limit);
}
