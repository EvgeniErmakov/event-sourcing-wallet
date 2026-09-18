package com.example.wallet.application;

import com.example.wallet.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.example.wallet.domain.WalletException.Code.*;

/**
 * Сценарии кошелька и явная граница атомарности. Не содержит SQL или сериализации.
 * Все изменения команды фиксируются до возврата результата; ошибки не кэшируются.
 * REQUIRES_NEW исключает возврат успеха до commit даже при вызове из внешней транзакции.
 */
@Service
public class WalletService {
    private final EventStore events;
    private final CommandReceiptRepository receipts;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public WalletService(EventStore events, CommandReceiptRepository receipts,
                         Clock clock, PlatformTransactionManager manager) {
        this.events = events;
        this.receipts = receipts;
        this.clock = clock;
        this.write = transaction(manager, false);
        this.read = transaction(manager, true);
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    /**
     * Сначала ищет прежний ответ, затем выполняет load → replay → decide → apply → append → receipt.
     * execute возвращает управление только после commit. При отказе версии/состояния и
     * адресной коллизии ключа execute сначала делает rollback; лишь затем читается receipt
     * в отдельной транзакции. Дубль, уже завершённый конкурентом, получает прежний ответ.
     * Никакого повторного decide/списания с обновлённой версией нет.
     */
    public CommandReceipt execute(UUID walletId, UUID commandId, WalletCommand command) {
        String fingerprint = CommandFingerprint.of(walletId, command);
        CommandReceipt previous = findReceipt(commandId);
        if (previous != null) return matching(previous, fingerprint);
        try {
            return Objects.requireNonNull(write.execute(ignored -> {
                Wallet wallet = restore(walletId, events.load(walletId));
                List<WalletEvent> decided = wallet.decide(command);
                // Первая версия поддерживает ровно один факт на успешную команду.
                WalletEvent event = decided.getFirst();
                long expectedVersion = wallet.version();
                wallet.apply(event);
                var occurredAt = clock.instant();
                events.append(walletId, expectedVersion, event, UUID.randomUUID(), commandId, occurredAt);
                CommandReceipt receipt = new CommandReceipt(commandId, fingerprint,
                        command instanceof WalletCommand.CreateWallet ? 201 : 200,
                        WalletState.from(wallet), clock.instant());
                receipts.save(receipt);
                return receipt;
            }));
        } catch (WalletException failure) {
            // Здесь транзакция записи уже завершилась rollback, её объект Wallet отброшен.
            // Не ловим произвольные SQL-ошибки как 409: они остаются серверными ошибками.
            CommandReceipt concurrent = findReceipt(commandId);
            if (concurrent != null) return matching(concurrent, fingerprint);
            throw failure;
        }
    }

    /** GET восстанавливает полный поток или его префикс; receipt никогда не читается. */
    public WalletState get(UUID walletId, Long atVersion) {
        if (atVersion != null && atVersion < 1) throw new WalletException(INVALID_REQUEST, "Некорректная версия");
        return inRead(() -> {
            List<StoredEvent> history = events.load(walletId);
            if (history.isEmpty()) throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
            if (atVersion != null && atVersion > history.getLast().streamVersion()) {
                throw new WalletException(VERSION_NOT_FOUND, "Историческая версия не найдена");
            }
            List<StoredEvent> prefix = atVersion == null ? history : history.stream()
                    .takeWhile(e -> e.streamVersion() <= atVersion).toList();
            return WalletState.from(restore(walletId, prefix));
        });
    }

    /** Курсорная история читает limit+1 строк; ограничение страницы не ограничивает replay. */
    public EventPage history(UUID walletId, long afterVersion, int limit) {
        if (afterVersion < 0 || limit < 1 || limit > 500) throw new WalletException(INVALID_REQUEST, "Некорректная пагинация");
        return inRead(() -> {
            if (!events.exists(walletId)) throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
            List<StoredEvent> found = events.readPage(walletId, afterVersion, limit + 1);
            boolean hasMore = found.size() > limit;
            List<StoredEvent> items = hasMore ? found.subList(0, limit) : found;
            return new EventPage(items, items.isEmpty() ? afterVersion : items.getLast().streamVersion(), hasMore);
        });
    }

    private Wallet restore(UUID walletId, List<StoredEvent> history) {
        return Wallet.rehydrate(walletId, history.stream().map(StoredEvent::payload).toList());
    }

    private CommandReceipt findReceipt(UUID commandId) {
        return inRead(() -> receipts.find(commandId).orElse(null));
    }

    private <T> T inRead(Supplier<T> action) { return read.execute(ignored -> action.get()); }

    private CommandReceipt matching(CommandReceipt receipt, String fingerprint) {
        if (!receipt.requestFingerprint().equals(fingerprint)) {
            throw new WalletException(IDEMPOTENCY_KEY_REUSED, "Ключ уже использован для другой команды");
        }
        return receipt;
    }
}
