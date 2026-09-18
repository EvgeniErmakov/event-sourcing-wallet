package com.example.wallet.service.model;

import com.example.wallet.domain.event.WalletEvent;
import java.time.Instant;
import java.util.UUID;

/** Envelope факта: порядок задаёт streamVersion; время и UUID не участвуют в replay. */
public record StoredEvent(
        UUID eventId,
        UUID walletId,
        long streamVersion,
        String eventType,
        int schemaVersion,
        WalletEvent payload,
        Instant occurredAt,
        UUID commandId
) {
}
