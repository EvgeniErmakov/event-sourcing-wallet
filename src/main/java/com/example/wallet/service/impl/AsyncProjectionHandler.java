package com.example.wallet.service.impl;

import com.example.wallet.exception.infrastructure.ProjectionIntegrityException;
import com.example.wallet.repository.EventStore;
import com.example.wallet.repository.ProjectionPositionRepository;
import com.example.wallet.service.ProjectionHandlerService;
import com.example.wallet.service.WalletReadModelProjector;
import com.example.wallet.service.model.ProjectionHandlerStatus;
import com.example.wallet.service.model.ProjectionPosition;
import com.example.wallet.service.model.StoredEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Надёжный polling событий из PostgreSQL. Каждая порция одного кошелька выполняется
 * в отдельной транзакции: строка позиции блокируется, факты применяются, затем позиция
 * и read model фиксируются одним commit. После rollback позиция остаётся прежней,
 * поэтому следующий цикл прочитает те же события. Это не exactly-once доставка;
 * отсутствие повторного эффекта даёт атомарность позиции и проекции.
 */
@Service
public class AsyncProjectionHandler implements ProjectionHandlerService {
    private static final Logger log = LoggerFactory.getLogger(AsyncProjectionHandler.class);

    private final EventStore events;
    private final ProjectionPositionRepository positions;
    private final WalletReadModelProjector projector;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private volatile boolean pauseRequested;
    private volatile boolean processing;
    private volatile String lastError;

    public AsyncProjectionHandler(EventStore events, ProjectionPositionRepository positions,
            WalletReadModelProjector projector, PlatformTransactionManager manager,
            @Value("${wallet.projection.batch-size:50}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("wallet.projection.batch-size должен быть положительным");
        }
        this.events = events;
        this.positions = positions;
        this.projector = projector;
        this.batchSize = batchSize;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Один polling-цикл не удерживает транзакцию на все кошельки. */
    @Scheduled(fixedDelayString = "${wallet.projection.poll-interval-ms:3000}",
            initialDelayString = "${wallet.projection.initial-delay-ms:1000}")
    public void poll() {
        if (pauseRequested) {
            return;
        }
        processing = true;
        try {
            for (UUID walletId : positions.findWalletIds()) {
                if (pauseRequested) {
                    break;
                }
                processWallet(walletId);
            }
        } finally {
            processing = false;
        }
    }

    private void processWallet(UUID walletId) {
        try {
            transaction.executeWithoutResult(status -> applyBatch(walletId));
        } catch (RuntimeException error) {
            // Ошибка одного потока не останавливает остальные. Следующий цикл
            // повторит попытку с прежней сохранённой позицией после паузы polling.
            lastError = "walletId=" + walletId + ": " + error.getMessage();
            log.error("Ошибка обработки проекции: walletId={}, причина={}", walletId, error.getMessage(), error);
        }
    }

    private void applyBatch(UUID walletId) {
        ProjectionPosition position = positions.lock(walletId);
        long expected = position.lastProcessedVersion();
        List<StoredEvent> batch = events.readAfter(walletId, expected, batchSize);
        for (StoredEvent event : batch) {
            long required = expected + 1;
            if (event.streamVersion() != required) {
                throw new ProjectionIntegrityException("Пропуск версии проекции: walletId=" + walletId
                        + ", expected=" + required + ", actual=" + event.streamVersion());
            }
            projector.apply(walletId, event.streamVersion(), event.payload());
            expected = event.streamVersion();
        }
        if (!batch.isEmpty()) {
            positions.update(walletId, position.lastProcessedVersion(), expected);
            log.debug("Порция проекции применена: walletId={}, fromVersion={}, toVersion={}, count={}",
                    walletId, position.lastProcessedVersion() + 1, expected, batch.size());
        }
    }

    @Override
    public ProjectionHandlerStatus status() {
        boolean paused = pauseRequested && !processing;
        String state = processing && pauseRequested ? "PAUSE_REQUESTED"
                : processing ? "RUNNING" : paused ? "PAUSED" : "IDLE";
        return new ProjectionHandlerStatus(state, paused, pauseRequested, processing, lastError);
    }

    @Override
    public ProjectionHandlerStatus pause() {
        pauseRequested = true;
        return status();
    }

    @Override
    public ProjectionHandlerStatus resume() {
        pauseRequested = false;
        lastError = null;
        return status();
    }
}
