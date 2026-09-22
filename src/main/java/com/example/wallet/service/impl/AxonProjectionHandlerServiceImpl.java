package com.example.wallet.service.impl;

import com.example.wallet.service.ProjectionHandlerService;
import com.example.wallet.service.model.ProjectionHandlerStatus;
import java.util.concurrent.CompletableFuture;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.springframework.stereotype.Service;

/** Управляет настоящим processor текущего экземпляра, не сбрасывая token.
 * Shutdown завершается после текущей обработки; до завершения future статус PAUSE_REQUESTED.
 * После рестарта Axon автоматически начинает с сохранённой позиции.
 */
@Service
public class AxonProjectionHandlerServiceImpl implements ProjectionHandlerService {
    private final Configuration configuration;
    private boolean pauseRequested;
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);
    public AxonProjectionHandlerServiceImpl(Configuration configuration) { this.configuration = configuration; }
    private StreamingEventProcessor processor() {
        return configuration.getComponents(StreamingEventProcessor.class).values().stream()
                .filter(p -> p.name().equals("wallet-projection")).findFirst().orElseThrow();
    }
    @Override
    public synchronized ProjectionHandlerStatus status() {
        var processor = processor();
        boolean finishing = pauseRequested && !stopping.isDone();
        // Завершение future с ошибкой не подтверждает остановку processor.
        boolean stopFailed = stopping.isCompletedExceptionally();
        boolean paused = pauseRequested && stopping.isDone() && !stopFailed && !processor.isRunning();
        String error = processor.processingStatus().values().stream().filter(s -> s.isErrorState())
                .map(s -> "Ошибка общего segment; подробности в журнале backend").findFirst().orElse(null);
        if (error == null && processor.isError()) {
            error = "Ошибка coordinator; подробности в журнале backend";
        }
        if (stopFailed) {
            error = "Не удалось подтвердить остановку обработчика; повторите управление";
        }
        return new ProjectionHandlerStatus(finishing ? "PAUSE_REQUESTED" : paused ? "PAUSED"
                : processor.isRunning() ? "RUNNING" : "IDLE", paused, pauseRequested, finishing || processor.isRunning(), error);
    }
    @Override
    public synchronized ProjectionHandlerStatus pause() {
        if (!pauseRequested || stopping.isCompletedExceptionally()) {
            // Синхронный отказ shutdown не должен оставлять ложный запрос паузы.
            var requestedStop = processor().shutdown();
            stopping = requestedStop;
            pauseRequested = true;
        }
        return status();
    }
    @Override
    public synchronized ProjectionHandlerStatus resume() {
        // Дожидаемся ещё выполняющейся остановки. Уже известная ошибка отражена в status,
        // но не должна навсегда запрещать следующую попытку управления этим экземпляром.
        if (!stopping.isCompletedExceptionally()) {
            stopping.join();
        }
        var processor = processor();
        if (!processor.isRunning()) {
            processor.start().join();
        }
        pauseRequested = false;
        stopping = CompletableFuture.completedFuture(null);
        return status();
    }
}
