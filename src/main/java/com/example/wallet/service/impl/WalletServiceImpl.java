package com.example.wallet.service.impl;

import static com.example.wallet.exception.domain.WalletException.Code.IDEMPOTENCY_KEY_REUSED;
import static com.example.wallet.exception.domain.WalletException.Code.INVALID_REQUEST;
import static com.example.wallet.exception.domain.WalletException.Code.VERSION_NOT_FOUND;
import static com.example.wallet.exception.domain.WalletException.Code.WALLET_NOT_FOUND;

import com.example.wallet.domain.Wallet;
import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.exception.domain.WalletException;
import com.example.wallet.repository.CommandReceiptRepository;
import com.example.wallet.repository.EventStore;
import com.example.wallet.service.CommandFingerprint;
import com.example.wallet.service.WalletService;
import com.example.wallet.service.model.CommandReceipt;
import com.example.wallet.service.model.EventPage;
import com.example.wallet.service.model.StoredEvent;
import com.example.wallet.service.model.WalletState;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Сценарии кошелька и явная граница атомарности. Не содержит SQL или сериализации.
 * Все изменения команды фиксируются до возврата результата; ошибки не кэшируются.
 * REQUIRES_NEW исключает возврат успеха до commit даже при вызове из внешней транзакции.
 */
@Service
public class WalletServiceImpl implements WalletService {
    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);

    private final EventStore events;
    private final CommandReceiptRepository receipts;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public WalletServiceImpl(EventStore events, CommandReceiptRepository receipts,
            Clock clock, PlatformTransactionManager manager) {
        this.events = events;
        this.receipts = receipts;
        this.clock = clock;
        this.write = transaction(manager, false);
        this.read = transaction(manager, true);
    }

    /**
     * Сначала ищет прежний ответ, затем выполняет load → replay → decide → apply → append → receipt.
     * execute возвращает управление только после commit. При отказе версии/состояния и
     * адресной коллизии ключа execute сначала делает rollback; лишь затем читается receipt
     * в отдельной транзакции. Дубль, уже завершённый конкурентом, получает прежний ответ.
     * Никакого повторного decide/списания с обновлённой версией нет.
     */
    @Override
    public CommandReceipt execute(UUID walletId, UUID commandId, WalletCommand command) {
        log.debug("Обработка команды: commandId={}, walletId={}, commandType={}, expectedVersion={}",
                commandId, walletId, command.getClass().getSimpleName(), command.expectedVersion());
        String fingerprint = CommandFingerprint.of(walletId, command);
        Optional<CommandReceipt> previous = findReceipt(commandId);
        if (previous.isPresent()) {
            return matching(previous.get(), fingerprint);
        }
        try {
            CommandReceipt completed = Objects.requireNonNull(write.execute(ignored ->
                    executeInTransaction(walletId, commandId, command, fingerprint)));
            // execute уже выполнил commit: до этой точки нельзя сообщать об успехе команды.
            log.info("Команда завершена: commandId={}, walletId={}, commandType={}, version={}",
                    commandId, walletId, command.getClass().getSimpleName(), completed.responseBody().version());
            return completed;
        } catch (WalletException failure) {
            // Здесь транзакция записи уже завершилась rollback, её объект Wallet отброшен.
            // Не ловим произвольные SQL-ошибки как 409: они остаются серверными ошибками.
            log.debug("Транзакция отменена: commandId={}, walletId={}, code={}; поиск receipt после rollback",
                    commandId, walletId, failure.code());
            return findReceipt(commandId)
                    .map(receipt -> matching(receipt, fingerprint))
                    .orElseThrow(() -> failure);
        }
    }

    /** GET восстанавливает полный поток или его префикс; receipt никогда не читается. */
    @Override
    public WalletState get(UUID walletId, Long atVersion) {
        log.debug("Чтение состояния: walletId={}, atVersion={}", walletId, atVersion);
        if (atVersion != null && atVersion < 1) {
            throw new WalletException(INVALID_REQUEST, "Некорректная версия");
        }
        return inRead(() -> {
            List<StoredEvent> history = events.load(walletId);
            if (history.isEmpty()) {
                throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
            }
            if (atVersion != null && atVersion > history.getLast().streamVersion()) {
                throw new WalletException(VERSION_NOT_FOUND, "Историческая версия не найдена");
            }
            List<StoredEvent> prefix = atVersion == null ? history : history.stream()
                    .takeWhile(e -> e.streamVersion() <= atVersion).toList();
            return WalletState.from(restore(walletId, prefix));
        });
    }

    /** Курсорная история читает limit+1 строк; ограничение страницы не ограничивает replay. */
    @Override
    public EventPage history(UUID walletId, long afterVersion, int limit) {
        log.debug("Чтение истории: walletId={}, afterVersion={}, limit={}", walletId, afterVersion, limit);
        if (afterVersion < 0 || limit < 1 || limit > 500) {
            throw new WalletException(INVALID_REQUEST, "Некорректная пагинация");
        }
        return inRead(() -> {
            if (!events.exists(walletId)) {
                throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
            }
            List<StoredEvent> found = events.readPage(walletId, afterVersion, limit + 1);
            boolean hasMore = found.size() > limit;
            List<StoredEvent> items = hasMore ? found.subList(0, limit) : found;
            long nextAfterVersion = items.isEmpty() ? afterVersion : items.getLast().streamVersion();
            log.debug("Страница истории прочитана: walletId={}, count={}, nextAfterVersion={}, hasMore={}",
                    walletId, items.size(), nextAfterVersion, hasMore);
            return new EventPage(items, nextAfterVersion, hasMore);
        });
    }

    /**
     * Выполняется внутри единственного write.execute, не открывает собственных транзакций.
     * Локальный Wallet после ошибки отбрасывается вместе с откатываемыми записями.
     */
    private CommandReceipt executeInTransaction(UUID walletId, UUID commandId,
            WalletCommand command, String fingerprint) {
        Wallet wallet = restore(walletId, events.load(walletId));
        List<WalletEvent> decided = wallet.decide(command);
        // Первая версия домена возвращает ровно один факт на успешную команду.
        WalletEvent event = decided.getFirst();
        long expectedVersion = wallet.version();
        wallet.apply(event);
        var occurredAt = clock.instant();
        UUID eventId = UUID.randomUUID();
        log.debug("Новый факт подготовлен: commandId={}, walletId={}, eventId={}, eventType={}, resultingVersion={}",
                commandId, walletId, eventId, event.getClass().getSimpleName(), wallet.version());
        events.append(walletId, expectedVersion, event, eventId, commandId, occurredAt);
        CommandReceipt receipt = new CommandReceipt(commandId, fingerprint,
                command instanceof WalletCommand.CreateWallet ? 201 : 200,
                WalletState.from(wallet), clock.instant());
        receipts.insert(receipt);
        log.debug("Событие и receipt вставлены; ожидается commit: commandId={}, walletId={}", commandId, walletId);
        return receipt;
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private Wallet restore(UUID walletId, List<StoredEvent> history) {
        log.debug("Replay начат: walletId={}, eventCount={}", walletId, history.size());
        Wallet wallet = Wallet.rehydrate(walletId, history.stream().map(StoredEvent::payload).toList());
        log.debug("Replay завершён: walletId={}, version={}", walletId, wallet.version());
        return wallet;
    }

    /** Отсутствие receipt — обычный результат; каждый поиск получает отдельную транзакцию чтения. */
    private Optional<CommandReceipt> findReceipt(UUID commandId) {
        Optional<CommandReceipt> receipt = inRead(() -> receipts.find(commandId));
        log.debug("Поиск receipt: commandId={}, found={}", commandId, receipt.isPresent());
        return receipt;
    }

    private <T> T inRead(Supplier<T> action) {
        return read.execute(ignored -> action.get());
    }

    private CommandReceipt matching(CommandReceipt receipt, String fingerprint) {
        if (!receipt.requestFingerprint().equals(fingerprint)) {
            throw new WalletException(IDEMPOTENCY_KEY_REUSED, "Ключ уже использован для другой команды");
        }
        log.debug("Возвращён сохранённый результат команды: commandId={}", receipt.commandId());
        return receipt;
    }
}
