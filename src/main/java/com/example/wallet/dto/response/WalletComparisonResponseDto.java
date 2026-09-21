package com.example.wallet.dto.response;

import com.example.wallet.service.model.WalletComparison;

/** Диагностический HTTP-ответ: readModel=null явно означает отсутствие производной строки. */
public record WalletComparisonResponseDto(WalletResponseDto eventState, WalletResponseDto readModel,
        long streamVersion, long projectionVersion, long pendingEvents, String status, boolean matches) {
    /** Преобразует независимые результаты чтения; null допускается только на границе HTTP DTO. */
    public static WalletComparisonResponseDto from(WalletComparison comparison) {
        return new WalletComparisonResponseDto(WalletResponseDto.from(comparison.eventState()),
                comparison.readModel().map(model -> WalletResponseDto.from(model.toState())).orElse(null),
                comparison.eventState().version(), comparison.projectionVersion(), comparison.pendingEvents(),
                comparison.status(), comparison.matches());
    }
}
