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
        boolean paused = pauseRequested && stopping.isDone() && !processor.isRunning();
        String error = processor.processingStatus().values().stream().filter(s -> s.isErrorState())
                .map(s -> "Ошибка общего segment; подробности в журнале backend").findFirst().orElse(null);
        if (error == null && processor.isError()) {
            error = "Ошибка coordinator; подробности в журнале backend";
        }
        return new ProjectionHandlerStatus(finishing ? "PAUSE_REQUESTED" : paused ? "PAUSED"
                : processor.isRunning() ? "RUNNING" : "IDLE", paused, pauseRequested, finishing || processor.isRunning(), error);
    }
    @Override
    public synchronized ProjectionHandlerStatus pause() {
        if (!pauseRequested) { pauseRequested = true; stopping = processor().shutdown(); }
        return status();
    }
    @Override
    public synchronized ProjectionHandlerStatus resume() {
        stopping.join();
        processor().start().join();
        pauseRequested = false;
        return status();
    }
}
