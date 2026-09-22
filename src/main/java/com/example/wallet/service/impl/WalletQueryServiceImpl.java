package com.example.wallet.service.impl;

import static com.example.wallet.exception.domain.WalletException.Code.INVALID_REQUEST;
import static com.example.wallet.exception.domain.WalletException.Code.PROJECTION_NOT_READY;
import static com.example.wallet.exception.domain.WalletException.Code.VERSION_NOT_FOUND;
import static com.example.wallet.exception.domain.WalletException.Code.WALLET_NOT_FOUND;

import com.example.wallet.domain.Wallet;
import com.example.wallet.exception.domain.WalletException;
import com.example.wallet.exception.infrastructure.ProjectionIntegrityException;
import com.example.wallet.repository.WalletHistory;
import com.example.wallet.repository.WalletReadModelRepository;
import com.example.wallet.service.WalletQueryService;
import com.example.wallet.service.model.EventPage;
import com.example.wallet.service.model.StoredEvent;
import com.example.wallet.service.model.WalletComparison;
import com.example.wallet.service.model.WalletReadModel;
import com.example.wallet.service.model.WalletState;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Обычное чтение использует производную таблицу, историческое и диагностическое — события.
 * JDBC-снимок относится только к проекции. История Axon читается независимо;
 * сравнение проверяет неизменяемый префикс событий на бизнес-версии ранее прочитанной проекции.
 */
@Service
public class WalletQueryServiceImpl implements WalletQueryService {
    private final WalletHistory events;
    private final WalletReadModelRepository models;
    private final TransactionTemplate read;

    public WalletQueryServiceImpl(WalletHistory events, WalletReadModelRepository models, PlatformTransactionManager manager) {
        this.events = events;
        this.models = models;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * При обычном GET нет replay и скрытого backfill. Отсутствие первой проекции — ожидаемый
     * временный результат асинхронной доставки и возвращается как 409 PROJECTION_NOT_READY.
     * Историческое чтение не зависит от готовности проекции и сохраняет прежние границы atVersion.
     */
    @Override
    public WalletState get(UUID walletId, Long atVersion) {
        if (atVersion != null && atVersion < 1) {
            throw new WalletException(INVALID_REQUEST, "Некорректная версия");
        }
        return Objects.requireNonNull(read.execute(ignored -> {
            if (atVersion == null) {
                return models.find(walletId).map(WalletReadModel::toState).orElseGet(() -> missingModel(walletId));
            }
            List<StoredEvent> history = requiredHistory(walletId);
            if (atVersion > history.getLast().streamVersion()) {
                throw new WalletException(VERSION_NOT_FOUND, "Историческая версия не найдена");
            }
            return restore(walletId, history.stream().takeWhile(e -> e.streamVersion() <= atVersion).toList());
        }));
    }

    /** {@inheritDoc} Курсор сдвигается только по реально прочитанным событиям. */
    @Override
    public EventPage history(UUID walletId, long afterVersion, int limit) {
        if (afterVersion < 0 || limit < 1 || limit > 500) {
            throw new WalletException(INVALID_REQUEST, "Некорректная пагинация");
        }
        return Objects.requireNonNull(read.execute(ignored -> {
            if (!events.exists(walletId)) {
                throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
            }
            List<StoredEvent> found = events.readPage(walletId, afterVersion, limit + 1);
            boolean hasMore = found.size() > limit;
            List<StoredEvent> items = hasMore ? found.subList(0, limit) : found;
            long next = items.isEmpty() ? afterVersion : items.getLast().streamVersion();
            return new EventPage(items, next, hasMore);
        }));
    }

    /**
     * Общего снимка нет: сначала читается проекция, затем история, включающая её префикс.
     * Отсутствующая производная строка возвращается явно, чтобы UI мог показать причину расхождения.
     */
    @Override
    public WalletComparison compare(UUID walletId) {
        return Objects.requireNonNull(read.execute(ignored -> {
            // Сначала фиксируем версию проекции, затем читаем неизменяемый префикс событий Axon.
            // Общего снимка нет: сравниваем содержимое на общей бизнес-версии.
            var readModel = models.find(walletId);
            var history = requiredHistory(walletId);
            WalletState eventState = restore(walletId, history);
            long projectionVersion = readModel.map(WalletReadModel::lastEventVersion).orElse(0L);
            if (projectionVersion > eventState.version()) {
                throw new ProjectionIntegrityException("Проекция опережает поток: walletId=" + walletId);
            }
            if (readModel.isPresent() && !restore(walletId, history.stream()
                    .takeWhile(event -> event.streamVersion() <= projectionVersion).toList()).equals(readModel.get().toState())) {
                throw new ProjectionIntegrityException("Состояния одной версии различаются: walletId=" + walletId);
            }
            long pending = eventState.version() - projectionVersion;
            return new WalletComparison(eventState, readModel, projectionVersion, pending,
                    pending == 0 ? "MATCHED" : "LAGGING");
        }));
    }

    private WalletState missingModel(UUID walletId) {
        if (events.streamExists(walletId)) {
            throw new WalletException(PROJECTION_NOT_READY, "Проекция кошелька ещё не готова");
        }
        throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
    }

    private List<StoredEvent> requiredHistory(UUID walletId) {
        List<StoredEvent> history = events.load(walletId);
        if (history.isEmpty()) {
            throw new WalletException(WALLET_NOT_FOUND, "Кошелёк не найден");
        }
        return history;
    }

    private WalletState restore(UUID walletId, List<StoredEvent> history) {
        return WalletState.from(Wallet.rehydrate(walletId, history.stream().map(StoredEvent::payload).toList()));
    }
}
