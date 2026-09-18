package com.example.wallet.dto.response;

import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.service.model.StoredEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * HTTP-envelope исторического факта. Поля и payload совпадают с прежним ответом истории.
 * Перенос Java-пакетов не изменяет eventType/schemaVersion и не преобразует факт в команду.
 */
public record EventResponseDto(
        UUID eventId,
        UUID walletId,
        long streamVersion,
        String eventType,
        int schemaVersion,
        WalletEvent payload,
        Instant occurredAt,
        UUID commandId
) {
    /** Копирует уже прочитанный факт для ответа; не выполняет replay или сериализацию в БД. */
    public static EventResponseDto from(StoredEvent event) {
        return new EventResponseDto(event.eventId(), event.walletId(), event.streamVersion(), event.eventType(),
                event.schemaVersion(), event.payload(), event.occurredAt(), event.commandId());
    }
}
