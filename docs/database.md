# PostgreSQL и Liquibase

Ветка сознательно использует новую схему с нуля. Master находится в
`src/main/resources/db/changelog/db.changelog-master.xml` и подключает два formatted SQL:

1. `001-event-sourcing.sql` — `event_streams`, `wallet_events`, `command_receipts`;
2. `002-async-projection.sql` — `wallet_read_model`, `projection_positions`.

Старые YAML master и миграции этапа 02 удалены. После формирования этой начальной схемы
changesets нельзя переписывать или переименовывать; дальнейшие изменения добавляются новыми
changesets. Приложение не очищает таблицы и не выполняет DDL из Java.

Liquibase — единственный механизм схемы. Не использовать Flyway, Hibernate DDL, `schema.sql`,
`data.sql` или создание таблиц из репозиториев. XML используется только для подключения
formatted SQL.

| Таблица | Назначение |
|---|---|
| `event_streams` | UUID потока и технический `current_version` для CAS |
| `wallet_events` | неизменяемые факты, источник истины |
| `command_receipts` | успешный ответ и fingerprint для идемпотентности |
| `wallet_read_model` | производное текущее состояние, может отставать |
| `projection_positions` | сохранённая позиция обработчика для каждого кошелька |

`projection_positions.last_processed_version=0` означает, что ни одно событие не применено.
Строка позиции создаётся в той же транзакции, что новый поток и его первое событие.
При обработке строка блокируется `SELECT ... FOR UPDATE`; read model и позиция меняются одним
commit отдельной транзакции. После rollback позиция остаётся прежней.

Командная транзакция содержит CAS `event_streams`, вставку события и receipt. Проектор в неё
не вызывается. Фоновая транзакция одного кошелька блокирует позицию, читает порцию событий после
курсора в `stream_version ASC`, проверяет `expected+1`, применяет факты и обновляет курсор.
Транзакция не охватывает список всех кошельков.

Глобальный sequence-курсор не используется: sequence выдаётся до commit и не гарантирует порядок
видимости конкурирующих транзакций. Надёжным порядком является версия внутри конкретного потока.

`wallet_read_model.last_event_version` — версия фактически применённого события. Если она меньше
версии потока, это обычное eventual consistency; если выше или равна, но состояние различается,
это `PROJECTION_INTEGRITY_ERROR`.

Для запуска используется Compose project `event-sourcing-wallet-03-cqrs-async` и volume
`wallet_async_postgres_data`. База этапа 02 не мигрируется и не backfill-ится. Необязательный
ручной сброс только этой базы:

```bash
docker compose down -v
docker compose up -d --wait postgres
```

Команду выполняет пользователь; приложение не удаляет volume автоматически.
