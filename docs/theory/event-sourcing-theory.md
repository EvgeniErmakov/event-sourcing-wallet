# Event Sourcing + CQRS + eventual consistency

Этот документ объясняет текущую ветку `03-cqrs-async` на примере кошелька.

## Три понятия

Event Sourcing хранит не итоговый баланс, а факты: кошелёк создан, деньги внесены, списаны,
кошелёк закрыт. `wallet_events` — источник истины. Чтобы получить состояние, Wallet применяет
факты по `stream_version`.

CQRS разделяет пути. Команда читает события, восстанавливает Wallet и проверяет правила.
Обычный запрос читает отдельную `wallet_read_model`. Одна БД не отменяет CQRS: разделены
модели и ответственность, а не обязательно физическая инфраструктура.

Eventual consistency появляется, когда событие уже committed, а read model ещё нет. Команда
не ждёт проекцию. Polling-обработчик позже применяет факты; пока он остановлен, GET может
вернуть старый баланс или 409 для ещё не созданной первой строки.

## Командный путь

```text
load events → replay Wallet → decide(command) → apply(event)
→ CAS event_streams → INSERT wallet_events → INSERT command_receipts → commit
```

В транзакцию входят поток, событие и receipt. Receipt хранит исходный успешный ответ для
Idempotency-Key и не является таблицей текущего состояния. Повтор того же fingerprint возвращает
receipt без нового события. Перед `getFirst()` сервис проверяет, что домен вернул ровно один факт.

## Позиция и обработка

У каждого кошелька есть `projection_positions.last_processed_version`. Ноль означает, что
события ещё не применялись. Обработчик открывает транзакцию на один кошелёк, делает
`SELECT ... FOR UPDATE`, читает ограниченную порцию после позиции и требует версии
`previous + 1`. Затем проектор меняет read model, а позиция и read model фиксируются вместе.

Если SQL, неизвестный тип, пропуск версии или переполнение приводят к ошибке, транзакция
откатывается. Позиция не сдвигается; следующий цикл повторяет события. Это не exactly-once
delivery: повторная попытка возможна, но зафиксированное применение не повторяется, потому что
курсор и результат применены атомарно.

Глобальный sequence-курсор опасен: sequence выдаётся до commit, поэтому конкурирующий commit
может стать видимым позже. Версия внутри каждого потока — единственный порядок replay.

## Проектор

`WalletReadModelProjector` применяет факты, а не команды:

```java
switch (event) {
    case WalletCreated created -> INSERT version 1;
    case MoneyDeposited deposited -> UPDATE balance + amount;
    case MoneyWithdrawn withdrawn -> UPDATE balance - amount;
    case WalletClosed closed -> UPDATE status CLOSED;
}
```

Для каждого UPDATE условие требует предыдущую версию. Upsert и молчаливое повторное применение
запрещены. Бизнес-решения остаются в Wallet.

## Чтение и сравнение

Обычный GET делает SELECT read model без replay и ожидания. Исторический GET и история читают
Event Store. Comparison в одном read-only `REPEATABLE READ` снимке получает replay-состояние,
read model, streamVersion, projectionVersion и pendingEvents. Если pending больше нуля — это
ожидаемое отставание. Одинаковая версия с разными полями или версия проекции выше потока —
ошибка целостности.

## Чистая БД

Liquibase создаёт пять таблиц: `event_streams`, `wallet_events`, `command_receipts`,
`wallet_read_model`, `projection_positions`. Старые базы этапов не backfill-ятся. Полная
административная пересборка проекции в этом учебном этапе отсутствует; изучение выполняется
новыми командами, паузой обработчика и перезапуском приложения.
