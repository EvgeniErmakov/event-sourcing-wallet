# Спецификация `03-cqrs-async`

Текущий этап — Event Sourcing + CQRS с асинхронной проекцией и eventual consistency.
Сохраняются бизнес-операции кошелька, CAS, fingerprint, receipts и прежний REST-контракт.

| | `02-cqrs-sync` | `03-cqrs-async` |
|---|---|---|
| Командная транзакция | событие + версия + проекция + receipt | событие + версия + receipt |
| Проекция | готова после commit команды | отдельный polling commit позже |
| Ошибка проекции | откатывает команду | откатывает порцию обработчика |
| Обычный GET | read model | read model, возможно старая |
| Курсор | не нужен | `projection_positions` на кошелёк |

Одна БД достаточна для CQRS: разделены модели и пути, а не обязательно физические базы.
После возобновления обработчика и устранения ошибок система сходится. При паузе или повреждённом
событии отставание может сохраняться.

## Сервисы

- `WalletCommandServiceImpl` — replay, decide/apply, CAS append и receipt.
- `WalletQueryServiceImpl` — read model, исторический replay, история и comparison.
- `AsyncProjectionHandler` — расписание, отдельная транзакция кошелька, lock позиции и retry.
- `WalletReadModelProjector` — применение событий с проверкой предыдущей версии.

Команда проверяет, что `Wallet.decide` вернул ровно одно событие. Receipt не является read model
и повтор успешной команды не запускает проектор.

## API и ограничения

Новые технические endpoints: `GET /api/projection-handler`,
`POST /api/projection-handler/pause`, `POST /api/projection-handler/resume`.
`GET /api/wallets/{id}` возвращает `PROJECTION_NOT_READY` (409), если поток уже есть, но
первая проекция ещё не создана. Comparison возвращает write-side state, read model/null,
streamVersion, projectionVersion, pendingEvents, status (`MATCHED`/`LAGGING`) и matches.

Нет брокера, отдельной БД, глобального курсора, snapshots, outbox, online rebuild, backfill и
переноса данных прошлых веток. Начальная схема рассчитана на чистую БД. Пауза не кластерная.
