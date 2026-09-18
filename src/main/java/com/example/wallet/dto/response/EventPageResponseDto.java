package com.example.wallet.dto.response;

import com.example.wallet.service.model.EventPage;
import java.util.List;

/** HTTP-страница с неизменяемым списком; семантика курсора определяется прикладным сервисом. */
public record EventPageResponseDto(
        List<EventResponseDto> items,
        long nextAfterVersion,
        boolean hasMore
) {
    public EventPageResponseDto {
        items = List.copyOf(items);
    }

    /** Отображает готовую страницу без изменения порядка и значений курсора. */
    public static EventPageResponseDto from(EventPage page) {
        return new EventPageResponseDto(page.items().stream().map(EventResponseDto::from).toList(),
                page.nextAfterVersion(), page.hasMore());
    }
}
