# AGENTS.md — Event Sourcing Wallet, этап 04

Ветка `04-axon` использует открытый Axon Framework 5.3.2, Java 25, Spring Boot 4.0.8,
Gradle 9.5.1 и PostgreSQL 17.11. Читать README, docs/PROJECT.md, docs/architecture.md,
docs/database.md, docs/axon.md и документы затронутого слоя перед изменениями.

## Границы и структура

JPA/Hibernate разрешены только для инфраструктуры aggregate-based Event Store Axon.
Wallet — event-sourced сущность Axon, не JPA Entity. Собственные receipts и read model — Spring JDBC.
Позиции — штатный JDBC Token Store. Нет Axon Server, коммерческих модулей Axoniq, DCB,
Spring Data repositories, Kafka, RabbitMQ, Temporal, snapshots, Saga, авторизации и новых бизнес-операций.

Controller — HTTP, exception — исключения. Интерфейсы сервисов — service,
реализации — service.impl. SQL приложения — repository.jdbc. Не создавать собственный
Event Store или polling параллельно Axon. Аннотации и API Axon в Wallet разрешены.
Конструкторное внедрение, final-зависимости и русские значимые комментарии обязательны.

## Транзакции и инварианты

- CommandGateway доставляет DispatchWalletCommand в Wallet; Axon восстанавливает сущность.
- Изменение состояния — через EventSourcingHandler. Replay не вызывает команды, часы и I/O записи.
- Ровно один WalletFact на команду, публичная businessVersion начинается с 1.
- Aggregate sequence начинается с 0; tracking token — глобальная техническая позиция, не версия кошелька.
- Один JpaTransactionManager и DataSource обслуживают SpringTransactionManager Axon и JdbcTemplate.
- Событие и receipt фиксируются одной транзакцией Axon. HTTP ждёт commit, включая flush.
- После rollback receipt конкурента читается в новой транзакции. Автоповторов денежных команд нет.
- Глобальный Idempotency-Key связан с fingerprint; старый receipt возвращается без нового события.
- Streaming processor фиксирует JDBC read model и JDBC token отдельной общей транзакцией.
- Один segment: ошибка блокирует его целиком, пауза относится к текущему экземпляру, resume не reset.
- Не заявлять exactly-once delivery. Не скрывать неизвестный бизнес-тип/формат события.
- Comparison проверяет проекцию на общей бизнес-версии: внешний RR не распространяется на чтение Axon.
- Деньги long/BIGINT в копейках, только RUB, положительные суммы, защита переполнения.

## Схема и запуск

Новая история Liquibase согласована для чистой БД этапа 04. XML master подключает
001-axon.sql и 002-wallet.sql. После формирования применённые changesets неизменяемы.
DDL только Liquibase: Hibernate ddl-auto=none; DDL из Java запрещён.
Compose project event-sourcing-wallet-04-axon, volume wallet_axon_postgres_data.
Старые БД/volumes не удалять. Порты совпадают с этапом 03; остановку выполняет пользователь.

## Проверки

Новых тестов не писать, существующие не запускать и не удалять. Не запускать test/check/build,
smoke/API/browser сценарии. Разрешены статический анализ, bootJar -x test и production-сборка frontend.
Отчёт различает факты исходников, компиляцию и неподтверждённые запуском гарантии.
