package com.example.wallet.service.model;

import java.util.List;

/** Страница фактов с курсором по версии; пустая страница сохраняет входной курсор. */
public record EventPage(
        List<StoredEvent> items,
        long nextAfterVersion,
        boolean hasMore
) {
    public EventPage {
        items = List.copyOf(items);
    }
}
