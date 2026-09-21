# Учебный маршрут: `03-cqrs-async`

Этап 02 фиксировал событие, проекцию и receipt одним commit. Здесь команда фиксирует только
write side, а `AsyncProjectionHandler` догоняет события отдельной транзакцией. Это позволяет
увидеть eventual consistency в одной БД без брокера.

## Что изучать

1. `Wallet` и `WalletEvent` — правила и факты.
2. `WalletCommandServiceImpl` — replay, CAS, fingerprint, receipt; проектор здесь отсутствует.
3. `JdbcEventStore` — версии потока и `readAfter`.
4. `ProjectionPositionRepository` — курсор и `FOR UPDATE`.
5. `AsyncProjectionHandler` — пакет, rollback, retry и pause.
6. `WalletReadModelProjector` — применение факта без бизнес-команды.
7. `WalletQueryServiceImpl` — stale GET и comparison в REPEATABLE READ.
8. `WalletController`, `ProjectionHandlerController`, Angular `AppComponent` и comparison.

## Ручной сценарий

1. Запустить PostgreSQL и приложение на чистой БД, создать кошелёк.
2. Дождаться `MATCHED` и `pendingEvents=0`.
3. Приостановить обработчик и дождаться `PAUSED`.
4. Пополнить кошелёк: команда успешна, событие и receipt сохранены, но read model ещё старая.
5. Повторить тот же запрос: receipt возвращается, новая версия события не появляется.
6. Возобновить обработчик и наблюдать `LAGGING`, затем `MATCHED`.
7. Остановить backend с накопившимися событиями, запустить снова и убедиться, что позиции
   продолжают обработку. Онлайн rebuild и перенос старой базы не входят в этап.

## Запуск

```bash
docker compose up -d --wait postgres
./gradlew bootRun
cd frontend && npm ci && npm start
```

Polling настраивается `WALLET_PROJECTION_POLL_INTERVAL_MS` и `WALLET_PROJECTION_BATCH_SIZE`.
Обычный GET не ждёт обработчик и не выполняет replay. Исторический просмотр и comparison
выполняют replay явно. Новые тесты и автоматические сценарии запрещены условиями этапа.
