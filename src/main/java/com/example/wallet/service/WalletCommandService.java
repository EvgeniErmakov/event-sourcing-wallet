package com.example.wallet.service;

import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.service.model.CommandReceipt;
import java.util.UUID;

/** Командная сторона CQRS: решения принимает Wallet из событий; проекция не служит входом команды. */
public interface WalletCommandService {
    /**
     * Выполняет команду или возвращает исходный успешный результат по её глобальному ключу.
     * Успех возвращается после commit события, версии потока, проекции и receipt. Другой fingerprint
     * того же ключа вызывает IDEMPOTENCY_KEY_REUSED; остальные отказы не сохраняются.
     * При конфликте новая версия для автоматического повтора команды не подбирается.
     */
    CommandReceipt execute(UUID walletId, UUID commandId, WalletCommand command);

}
