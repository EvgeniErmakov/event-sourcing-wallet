package com.example.wallet.service.model;

import java.util.Optional;

/** Два независимо прочитанных состояния одного снимка. Отсутствие проекции никогда не является совпадением. */
public record WalletComparison(WalletState eventState, Optional<WalletReadModel> readModel) {
    /** Сравнивает все поля состояния, включая версию; не ограничивается совпадением баланса. */
    public boolean matches() {
        return readModel.map(model -> eventState.equals(model.toState())).orElse(false);
    }
}
