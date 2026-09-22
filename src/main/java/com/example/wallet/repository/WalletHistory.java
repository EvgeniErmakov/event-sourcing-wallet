package com.example.wallet.repository;

import com.example.wallet.service.model.StoredEvent;
import java.util.List;
import java.util.UUID;

/** Только чтение истории через Axon; собственной записи и CAS больше нет. */
public interface WalletHistory {
    List<StoredEvent> load(UUID walletId);
    default boolean exists(UUID walletId) { return !load(walletId).isEmpty(); }
    default boolean streamExists(UUID walletId) { return exists(walletId); }
    default List<StoredEvent> readPage(UUID walletId, long afterVersion, int limit) {
        return load(walletId).stream().filter(e -> e.streamVersion() > afterVersion).limit(limit).toList();
    }
}
