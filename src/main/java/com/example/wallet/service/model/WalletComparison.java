package com.example.wallet.service.model;

import java.util.Optional;

/** Последовательные наблюдения моделей; целостность проверяется на общей бизнес-версии. Отсутствие проекции никогда не является совпадением. */
public record WalletComparison(WalletState eventState, Optional<WalletReadModel> readModel,
        long projectionVersion, long pendingEvents, String status) {
    /** Сравнивает все поля состояния, включая версию; не ограничивается совпадением баланса. */
    public boolean matches() {
        return readModel.map(model -> eventState.equals(model.toState())).orElse(false);
    }
}
