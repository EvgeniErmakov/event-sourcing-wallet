# event-sourcing-wallet — `03-cqrs-async`

Учебный кошелёк на Java 25, Spring Boot 4, PostgreSQL, Spring JDBC и Liquibase.
Этап показывает Event Sourcing + CQRS с асинхронной проекцией и eventual consistency.
В одной БД остаются две модели: `wallet_events` — источник истины для команд, а
`wallet_read_model` — производное текущее состояние для обычного GET.

Event Sourcing, CQRS и eventual consistency — разные идеи. Event Sourcing хранит факты,
CQRS разделяет write/read пути, а eventual consistency означает, что read model обновляется
позже отдельным обработчиком. Отдельная БД или брокер для этой демонстрации не нужны.

## Версии

| Компонент | Версия |
|---|---|
| Java | 25 |
| Spring Boot | 4.0.8 |
| Gradle Wrapper | 9.5.1 |
| PostgreSQL image | 17.11-bookworm |
| Angular | 22.1.7 |
| Node.js | 24.21.0 |

Нужны JDK 25, Docker Compose v2 и свободные порты 28741 (API), 28742 (PostgreSQL),
28743 (Angular dev server).

## Чистая БД

Ветка использует отдельный Compose project `event-sourcing-wallet-03-cqrs-async` и volume
`wallet_async_postgres_data`. Запуск поверх БД этапов 01/02 не поддерживается: старые события
не заполняют `projection_positions` и read model автоматически. Приложение не очищает данные
самостоятельно.

Liquibase запускает `src/main/resources/db/changelog/db.changelog-master.xml`, который подключает:

- `001-event-sourcing.sql`: `event_streams`, `wallet_events`, `command_receipts`;
- `002-async-projection.sql`: `wallet_read_model`, `projection_positions`.

Все DDL выполняется только Liquibase. После формирования этой начальной схемы changesets
считаются неизменяемыми. Backfill, административная пересборка и перенос истории прошлых веток
в текущем этапе не реализованы.

## Запуск

```bash
docker compose up -d --wait postgres
./gradlew bootRun
```

Для сборки без запуска приложения:

```bash
./gradlew bootJar -x test
```

Остановка без удаления данных:

```bash
docker compose stop postgres
```

Необязательный ручной сброс только базы этапа 03 (удалит все данные его volume):

```bash
docker compose down -v
docker compose up -d --wait postgres
```

Переменные приложения: `DB_URL` (по умолчанию `jdbc:postgresql://127.0.0.1:28742/wallet`),
`DB_USERNAME=wallet`, `DB_PASSWORD=wallet`. Параметры обработчика:
`WALLET_PROJECTION_POLL_INTERVAL_MS=3000`, `WALLET_PROJECTION_INITIAL_DELAY_MS=1000`,
`WALLET_PROJECTION_BATCH_SIZE=50`.

## Транзакции и обработчик

Команда выполняет replay, бизнес-проверки и CAS-append. В одной транзакции фиксируются
`event_streams.current_version`, событие и `command_receipts`; read model не блокирует HTTP-ответ.
Команда возвращает результат write side после commit. Receipt сохраняет идемпотентность и не
является read model.

`AsyncProjectionHandler` polling-циклом перечисляет кошельки из `projection_positions`.
Для каждого кошелька открывается отдельная транзакция, позиция блокируется `SELECT FOR UPDATE`,
читается ограниченная порция `stream_version`, проверяется непрерывность, затем проектор меняет
read model и позиция фиксируется одним commit. Ошибка откатывает обе записи; следующий цикл
прочитает события снова с прежней позиции. Это не exactly-once delivery, но зафиксированные
события не меняют баланс повторно благодаря атомарности позиции и проекции.

Порядок версий кошелька используется вместо глобального sequence: commit конкурирующих команд
может завершаться не в порядке выдачи sequence. Пауза одного экземпляра не останавливает запись
команд; после перезапуска позиции загружаются из PostgreSQL.
После запуска флаг паузы сброшен; до первого polling статус может быть `IDLE`.

Техническое управление текущим экземпляром:

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/projection-handler` | статус (`RUNNING`, `PAUSE_REQUESTED`, `PAUSED`, `IDLE`) и последняя ошибка |
| POST | `/api/projection-handler/pause` | запрет новых порций |
| POST | `/api/projection-handler/resume` | продолжить polling |

Пауза не кластерная: второй экземпляр приложения её не увидит. Уже начатая транзакция может
завершиться.

## API чтения

Обычный `GET /api/wallets/{id}` читает только read model. Если событие уже сохранено, а первая
проекция ещё не создана, ответ — `409 PROJECTION_NOT_READY`; replay и ожидание внутри GET не
выполняются. Отсутствующий поток даёт прежний `404 WALLET_NOT_FOUND`. При отставании GET
возвращает фактическую старую версию `last_event_version`.

`GET /api/wallets/{id}?atVersion=N` и `/events` читают Event Store независимо от проекции.
`GET /api/wallets/{id}/comparison` в одном `REPEATABLE READ` снимке возвращает:

```json
{
  "eventState": { "walletId": "…", "balanceMinor": 1000, "currency": "RUB", "status": "ACTIVE", "version": 2 },
  "readModel": { "walletId": "…", "balanceMinor": 0, "currency": "RUB", "status": "ACTIVE", "version": 1 },
  "streamVersion": 2,
  "projectionVersion": 1,
  "pendingEvents": 1,
  "status": "LAGGING",
  "matches": false
}
```

Одинаковая версия с разными полями или версия проекции выше потока — `PROJECTION_INTEGRITY_ERROR`.
Отставание — ожидаемое состояние и не считается повреждением.

## Angular UI

```bash
cd frontend
npm ci
npm start
```

UI сохраняет создание, пополнение, списание, закрытие, историю, `atVersion` и повтор с прежним
`Idempotency-Key`. Он показывает последнюю команду отдельно от read model, статус обработчика,
версии write/read side и число ожидающих событий. Чтение и сравнение обновляются периодически без
пересечения одинаковых запросов; после смены кошелька старые ответы отбрасываются.

Для `expectedVersion` используется версия показанной read model. Пока проекция отсутствует или
отстаёт от результата последней команды, новые операции UI временно отключены. Backend при этом
остаётся независимым от read model.

Ручной сценарий:

1. Создайте кошелёк и дождитесь совпадения моделей.
2. Нажмите паузу обработчика и дождитесь `PAUSED`.
3. Пополните кошелёк: команда завершится, событие появится, а read model останется прежней.
4. Повторите тот же запрос и убедитесь, что новая версия события не появилась.
5. Возобновите обработчик и наблюдайте статус `LAGGING`, затем совпадение.
6. Для проверки продолжения остановите backend с накопившимися событиями и запустите его снова.

## Документы и ограничения

Архитектура: [docs/architecture.md](docs/architecture.md), схема БД:
[docs/database.md](docs/database.md), API: [docs/api-guidelines.md](docs/api-guidelines.md),
учебный маршрут: [docs/LEARNING.md](docs/LEARNING.md), frontend:
[docs/frontend.md](docs/frontend.md), теория: [docs/theory/event-sourcing-theory.md](docs/theory/event-sourcing-theory.md).

Новые тесты не создавались и существующие тесты не запускались. Сборка не является доказательством
конкурентного поведения; административная пересборка проекции, backfill и онлайн-пауза кластера
остаются за пределами этапа.
