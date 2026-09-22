# PostgreSQL и Liquibase этапа 04

XML master `src/main/resources/db/changelog/db.changelog-master.xml` подключает:

- `001-axon.sql`: `"AggregateEventEntry"`, sequence `"aggregate-event-global-index-sequence"`,
  JDBC `TokenEntry` и начальный segment 0 с mask 0 и пустым token;
- `002-wallet.sql`: `command_receipts`, `wallet_read_model`.

Схема взята из AggregateEventEntry и GenericTokenTableFactory версии Axon 5.3.2.
Hibernate использует PhysicalNamingStrategyStandardImpl и globally_quoted_identifiers;
JPA имена сохраняют регистр. @Lob byte[] отображается PostgreSQL dialect в OID (large objects),
JDBC token хранится BYTEA. Sequence allocationSize=1. Уникальная пара aggregateIdentifier /
aggregateSequenceNumber защищает конкурирующий append; Hibernate flush вызывается Axon до commit.
Начальное значение aggregate sequence — 0; WalletFact.businessVersion начинается с 1.

JDBC TokenStore использует некавыченные имена, приведённые PostgreSQL к нижнему регистру.
Колонка mask обязательна для API 5.3.2. Технические обновления tokens делает библиотека;
DDL, начальный segment и служебный ConfigToken (`__config`) создаёт только Liquibase. Hibernate ddl-auto=none.

JPA события и JDBC receipt участвуют в одной транзакции JpaTransactionManager через общий
DataSource; JDBC read model и token — в другой транзакции processor. Не добавлять FK
к бывшему event_streams: этой таблицы больше нет. Источник истины — события Axon.

Чистая БД обязательна. Compose project `event-sourcing-wallet-04-axon`, volume
`wallet_axon_postgres_data`. Порты совпадают с этапом 03; остановите его перед запуском.
Запуск и необязательный ручной сброс только БД этапа 04 описаны в [README](../README.md).
Старые БД и volumes не удаляются. Применённые changesets нельзя изменять: новые изменения
требуют новых formatted SQL, подключённых XML master. Перенос истории и rebuild не реализованы.
