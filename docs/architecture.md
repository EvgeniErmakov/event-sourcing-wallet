# Архитектура этапа 04

Одна PostgreSQL хранит aggregate-based события Axon, JDBC tokens, receipts и read model.
Wallet — event-sourced модель с бизнес-инвариантами; JPA Entity только служебная AggregateEventEntry.

```text
HTTP → WalletCommandServiceImpl → CommandGateway → Wallet.handle
  → Axon EventSourcingRepository → source исторических фактов → decide
  → EventAppender → Wallet.source → JDBC receipt → flush события → commit → HTTP

PooledStreamingEventProcessor (wallet-projection, один segment)
  → AxonWalletProjection → WalletReadModelProjector → JDBC read model
  → JdbcTokenStore → commit общей транзакции порции
```

AxonConfiguration связывает SpringTransactionManager Axon с JpaTransactionManager,
EntityManagerProvider и SpringDataSourceConnectionProvider. JdbcTemplate использует тот же DataSource.
Процессор получает TransactionalUnitOfWorkFactory из конфигурации Axon; исключение проектора
распространяется и откатывает порцию вместе с token. Ошибка блокирует общий segment.

Query service читает read model через JDBC, историю — AxonWalletHistory через EventStore API.
Для comparison проекция читается раньше истории и сверяется с историческим префиксом её версии.
Чтение Axon не наследует JDBC REPEATABLE READ; сравнение не объявляется единым снимком.

Controller — транспорт; service/impl — прикладные сценарии; domain — Wallet и факты;
repository.jdbc — собственный SQL. События не дублируются в прежней wallet_events.
Подробности версий, атомарности, паузы и ограничений: [axon.md](axon.md).
