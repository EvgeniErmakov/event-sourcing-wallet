package com.example.wallet.domain.event;

import java.util.UUID;
import org.axonframework.eventsourcing.annotation.EventTag;

/** Один факт Axon; businessVersion начинается с 1 и не является tracking token. */
@org.axonframework.messaging.eventhandling.annotation.Event(namespace = "wallet", name = "WalletFact", version = "1")
public record WalletFact(@EventTag(key = "Wallet") UUID walletId, long businessVersion,
        UUID commandId, String eventType, int schemaVersion, String payload) {
    public static final String EVENT_NAME = "wallet.WalletFact";
}
