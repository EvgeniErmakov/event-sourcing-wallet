# AGENTS.md — Event Sourcing Wallet, этап 03

Ветка `03-cqrs-async` продолжает `02-cqrs-sync` и демонстрирует Event Sourcing,
CQRS и eventual consistency в одном Spring Boot приложении и одной PostgreSQL.
Команды и события записываются независимо от готовности read model; отдельный polling-
обработчик применяет события к производной таблице.

## Стек и границы

Сохранять Java 25, Spring Boot 4.0.8, Gradle 9.5.1, PostgreSQL 17.11, Spring JDBC,
Liquibase и Angular UI. Не добавлять Axon, JPA/Hibernate, Spring Data, Lombok, MapStruct,
Kafka, RabbitMQ, Temporal, snapshots, outbox, авторизацию, отдельную БД или новые бизнес-операции.
Не строить универсальный command bus или framework.

Перед изменением читать `README.md`, `docs/PROJECT.md`, `docs/architecture.md`,
`docs/database.md` и документы затрагиваемого слоя.

## Структура

- REST-контроллеры находятся в `controller`, исключения — в `exception`.
- Интерфейсы прикладных сервисов находятся в `service`, реализации — в `service.impl`.
- Domain не зависит от Spring, JDBC, Jackson, HTTP и DTO.
- SQL находится в `repository.jdbc`; контроллеры не содержат SQL и бизнес-правил.
- Constructor injection и явные `final`-поля обязательны.
- JavaDoc и значимые комментарии пишутся на русском языке.

## Ответственность компонентов

`WalletCommandServiceImpl` восстанавливает `Wallet` из событий, проверяет правила,
вызывает CAS-append и вставляет receipt. В его транзакции входят только поток, событие
и receipt; read model туда не входит.

`AsyncProjectionHandler` по расписанию читает сохранённую позицию каждого кошелька,
блокирует её `SELECT FOR UPDATE`, применяет ограниченную порцию событий через
`WalletReadModelProjector` и в одной отдельной транзакции обновляет read model и позицию.
Ошибка откатывает обе записи; следующий цикл повторяет ту же позицию.

`WalletQueryServiceImpl` читает обычный GET только из `wallet_read_model`, исторические
версии и историю — из Event Store. Сравнение получает replay и read model в одном
read-only `REPEATABLE READ` снимке. Отсутствующая первая проекция даёт
`409 PROJECTION_NOT_READY`, а не скрытый replay.

## Инварианты Event Sourcing

1. `wallet_events` — единственный источник истины; `wallet_read_model` производна.
2. `stream_version` начинается с 1 и не имеет пропусков; порядок replay — только по версии.
3. Команда создаёт ровно одно событие; это проверяется до `getFirst()`.
4. CAS по `expectedVersion`, fingerprint и receipt сохраняют конкурентную запись и идемпотентность.
5. Receipt только вставляется и читается; повтор с тем же fingerprint возвращает исходный ответ.
6. Позиция `projection_positions.last_processed_version=0` означает отсутствие применённых событий.
7. Версия read model меняется только вместе с позицией обработчика.
8. Не называть механизм exactly-once delivery: атомарность позиции и проекции не даёт повторного
   эффекта после commit, но доставка и повтор попытки сами по себе не являются exactly-once.
9. Не использовать глобальный sequence-курсор: commit конкурирующих транзакций не обязан следовать
   порядку sequence.
10. Деньги — `long`/`BIGINT` в копейках, переполнение запрещено.

## Liquibase

Для этой ветки начальная схема заменена с нуля по явному разрешению пользователя.
Действующий master — `src/main/resources/db/changelog/db.changelog-master.xml`,
он подключает formatted SQL `001-event-sourcing.sql` и `002-async-projection.sql`.
Старые YAML/SQL-файлы удалены. После создания новой схемы changesets считаются
неизменяемыми: дальнейшие изменения добавляются новыми changesets.

Liquibase — единственный механизм DDL. Не использовать `schema.sql`, `data.sql`, Flyway,
Hibernate DDL или создание таблиц из Java. Приложение не очищает существующую БД.

## Чистая БД и проверки

Этап использует отдельный Compose project/volume `event-sourcing-wallet-03-cqrs-async`.
Запуск поверх базы 02 и backfill старой истории не поддерживаются. Не удалять БД и volume
автоматически; ручной сброс описан в README.

Новые тесты не создавать и существующие тесты не запускать. Разрешены статические проверки,
`./gradlew bootJar -x test` и production-сборка frontend. Не сообщать о runtime-поведении как
о проверенном без фактического запуска.
