package com.example.wallet.service;

import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.service.model.CommandReceipt;
import com.example.wallet.service.model.EventPage;
import com.example.wallet.service.model.WalletState;
import java.util.UUID;

/**
 * Прикладной контракт кошелька, от которого зависит контроллер.
 * Реализация организует replay и атомарную запись, а бизнес-решения принимает Wallet.
 * Интерфейс не определяет HTTP DTO и не раскрывает JDBC или механизм транзакций.
 */
public interface WalletService {
    /**
     * Выполняет команду или возвращает исходный успешный результат по её глобальному ключу.
     * Успех возвращается после commit события, версии потока и receipt. Другой fingerprint
     * того же ключа вызывает IDEMPOTENCY_KEY_REUSED; остальные отказы не сохраняются.
     * При конфликте новая версия для автоматического повтора команды не подбирается.
     */
    CommandReceipt execute(UUID walletId, UUID commandId, WalletCommand command);

    /**
     * Восстанавливает состояние из фактов: null atVersion означает текущую версию,
     * положительное значение — исторический префикс. Отсутствующий кошелёк/версия — ошибка.
     * Не изменяет историю и не использует receipt как источник баланса.
     */
    WalletState get(UUID walletId, Long atVersion);

    /**
     * Возвращает страницу событий после afterVersion >= 0 с limit от 1 до 500.
     * Пустая страница сохраняет курсор; отсутствие самого кошелька является ошибкой.
     * Ограничение страницы не распространяется на полный replay при других операциях.
     */
    EventPage history(UUID walletId, long afterVersion, int limit);
}
