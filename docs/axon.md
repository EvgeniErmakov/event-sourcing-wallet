# Axon Framework: этап 04

## Версии и выбор инфраструктуры

Используется открытый **Axon Framework 5.3.2** и Spring extension **5.3.2**.
Выбрано aggregate-based JPA-хранилище в PostgreSQL; Axon Server и коммерческие модули
Axoniq отсутствуют. Java 25, Spring Boot 4.0.8 и Gradle 9.5.1 сохранены.
Разрешённые зависимости фактически разрешились в Hibernate 7.2.24.Final, Spring ORM 7.0.9
и PostgreSQL JDBC 42.7.13. Spring Data не используется.

Официальные источники, проверенные при выборе:

- [Опубликованные версии axon-eventsourcing](https://repo.maven.apache.org/maven2/org/axonframework/axon-eventsourcing/maven-metadata.xml).
- [Spring Boot integration 5.3](https://docs.axoniq.io/axon-framework-reference/5.3/spring-boot-integration/):
  поддержка Boot 4 начиная с 5.0.3.
- [Release notes 5.0.3](https://docs.axoniq.io/axon-framework-reference/5.0/release-notes/minor-releases/):
  минимальная Java 21, сборка на JDK 25.
- [Сравнение инфраструктуры Framework](https://docs.axoniq.io/axon-framework-reference/5.3/framework-comparison/):
  открытый aggregate-based JPA Event Store и JDBC Token Store.
- [Исходники eventsourcing 5.3.2](https://repo.maven.apache.org/maven2/org/axonframework/axon-eventsourcing/5.3.2/axon-eventsourcing-5.3.2-sources.jar):
  `AggregateBasedJpaEventStorageEngine`, `AggregateEventEntry`, `SimpleEntityLifecycleHandler`.
- [Исходники messaging 5.3.2](https://repo.maven.apache.org/maven2/org/axonframework/axon-messaging/5.3.2/axon-messaging-5.3.2-sources.jar):
  `JdbcTokenStore`, `WorkPackage`, `TransactionalUnitOfWorkFactory`.
- [Исходники Spring extension 5.3.2](https://repo.maven.apache.org/maven2/org/axonframework/extensions/spring/axon-spring/5.3.2/axon-spring-5.3.2-sources.jar):
  `SpringTransactionManager`, `SpringDataSourceConnectionProvider`.
- [Исходники Boot integration 5.3.2](https://repo.maven.apache.org/maven2/org/axonframework/extensions/spring/axon-spring-boot-autoconfigure/5.3.2/axon-spring-boot-autoconfigure-5.3.2-sources.jar):
  `JpaEventStoreAutoConfiguration`.

JPA здесь — способ хранения неизменяемых событий штатной инфраструктурой Axon. Wallet
не является JPA-сущностью: его баланс восстанавливается применением истории. У aggregate-based
хранилища события принадлежат конкретному агрегату и имеют его sequence number. DCB позволяет
определять границу согласованности критериями событий и тегов; JPA engine выбранной версии
не реализует DCB. Наличие EventCriteria в API Axon 5 само по себе не превращает этот engine в DCB.
Один тег `Wallet=<UUID>` соответствует одному кошельку.

## Конфигурация

`AxonConfiguration` задаёт общий `JpaTransactionManager`, `EntityManagerProvider`, адаптер
транзакций Axon и постоянный `JdbcTokenStore`. Boot-конфигурация Axon выбирает
`AggregateBasedJpaEventStorageEngine` при наличии EntityManagerFactory.
`PooledStreamingEventProcessorModule` с именем `wallet-projection` регистрирует
`AxonWalletProjection`: один segment, максимум один занятый segment, порция 50 событий.
Ошибки распространяются через `PropagatingErrorHandler`.

`axon.eventstorage.jpa.polling-interval=3000` задаёт обнаружение новых событий JPA coordinator.
Локальное уведомление может сработать раньше; искусственной задержки команд нет.
Hibernate DDL отключён (`ddl-auto=none`), Open EntityManager in View отключён.
Физические имена JPA сохраняются стандартной naming strategy и заключаются в кавычки.
Liquibase создаёт таблицы и начальные служебные записи; методы создания схемы Axon не вызываются.
Подробности колонок и PostgreSQL OID — в [database.md](database.md).

## Одна команда и восстановление состояния

1. HTTP-сервис вычисляет прежний fingerprint и проверяет receipt в короткой транзакции чтения.
2. `CommandGateway` принимает `DispatchWalletCommand`; `@TargetEntityId` выбирает UUID.
3. Axon загружает `Wallet`, созданный через `@EntityCreator`, и применяет события через
   `@EventSourcingHandler`. Replay не вызывает команды, часы и запись receipt.
4. Обработчик повторно проверяет receipt, затем `Wallet.decide()` проверяет expectedVersion,
   RUB, целые копейки, положительность суммы, переполнение, баланс и статус.
5. Явная проверка требует ровно один факт до `getFirst()`. `EventAppender` добавляет `WalletFact`
   и применяет его к состоянию. Сформированный ответ сохраняется в JDBC receipt.
6. Axon завершает UnitOfWork: события сохраняются и flush/commit заканчиваются до успешного
   завершения gateway. Только после этого контроллер возвращает успешный ответ.

Публичная `businessVersion` начинается с 1. Aggregate sequence Axon начинается с 0.
Tracking token описывает позицию processor, а не версию или число событий данного кошелька.
Приложение не использует технический sequence как expectedVersion.

## Реальные транзакции JPA и JDBC

Транзакция команды принадлежит Axon UnitOfWork. Внешней `@Transactional` вокруг gateway нет.
`SpringTransactionManager` Axon использует наш `JpaTransactionManager` и propagation REQUIRES_NEW.
Он регистрирует JPA/JDBC executors в ProcessingContext и требует исполнения в одном потоке;
`TransactionalUnitOfWorkFactory` учитывает это требование. Shared EntityManager и
`SpringDataSourceConnectionProvider` работают с тем же DataSource, который использует JdbcTemplate.
`JpaTransactionManager` привязывает JDBC connection к текущей транзакции, поэтому receipt
не фиксируется отдельно от события.

JPA append выполняет `persist` и `flush`; constraint violation может проявиться позднее, чем
вызов обработчика. Успех самого `Wallet.handle()` ещё не является успехом HTTP. Ошибка flush
или commit завершает gateway ошибкой и откатывает также JDBC receipt. После возврата ошибки
сервис читает receipt конкурента в новой REQUIRES_NEW-транзакции.

Первичный ключ receipt глобален для всех кошельков. INSERT с адресным ON CONFLICT ожидает
конкурента; отсутствие вставки прерывает текущую транзакцию. Совпавший fingerprint возвращает
первоначальный статус и тело; другой fingerprint даёт конфликт. Unique aggregate version
в Event Store защищает конкурентные записи одного кошелька. Автоматического повтора денежной
команды с новой версией или ключом нет. Axon сам не заменяет эту бизнес-идемпотентность.

Фоновая обработка имеет **другой** UnitOfWork и отдельную транзакцию. JdbcTemplate обновляет
read model, а `WorkPackage` сохраняет token в prepare-commit через executor того же контекста.
`JdbcTokenStore` получает контекстный JDBC executor Spring; при служебных операциях без контекста
он открывает собственную короткую транзакцию. Это не отдельный commit позиции внутри порции.
Ошибка проектора откатывает и read model, и token.

Эти связи проверены по коду приложения и исходникам 5.3.2. Сборка не проверяет фактические
блокировки, flush/rollback и сбои процесса: runtime-сценарии и тесты по условиям задания не запускались.

## Асинхронная проекция и продолжение

Источник processor — сохранённый Event Store. In-memory очередь не служит механизмом доставки.
После рестарта processor начинает с JDBC token. После rollback порция читается снова; после
commit продвижение token и изменение баланса сохраняются вместе. Это не exactly-once delivery.
Версия проекции дополнительно проверяется проектором: пропуск или повтор версии вызывает ошибку.

Обработчик подписан на все типы, явно проверяет имя/версию `WalletFact`, а затем декодирует
бизнес-факт прежним EventSerializer. Неизвестный формат или повреждённый payload не пропускается.
Один segment и одна последовательность обеспечивают порядок; ошибка может задержать **все**
кошельки. Обещания изоляции ошибок по UUID из этапа 03 больше не применяются.
Штатный gap-aware механизм Axon учитывает пропуски глобальной последовательности; приложение
не строит собственный курсор `globalIndex > lastSeen`.

`AxonProjectionHandlerServiceImpl` вызывает настоящий `shutdown()`/`start()` processor текущего
экземпляра. Пока shutdown future не завершена, возвращается PAUSE_REQUESTED; после остановки —
PAUSED. Уже начатая порция может завершиться. Resume не вызывает reset и сохраняет token.
Ошибка shutdown не считается подтверждением паузы: статус содержит lastError, управление
можно повторить. Сохранённая ошибка предыдущей остановки не блокирует все последующие resume.
Флаг паузы живёт в памяти: после перезапуска обработка включена. Это не кластерная пауза;
ручная демонстрация рассчитана на один backend. Ошибки segment доступны через processingStatus.

## История, HTTP DTO и сравнение

`AxonWalletHistory` использует `EventStore.transaction(context).source(...)`, EventConverter
и EventSerializer, без SQL к таблицам Axon. Второго Event Store и копии `wallet_events` нет.

| HTTP-поле | Источник |
|---|---|
| eventId | Axon message identifier |
| walletId | WalletFact.walletId / тег Wallet |
| streamVersion | WalletFact.businessVersion |
| eventType, schemaVersion, payload | стабильный бизнес-формат EventSerializer |
| occurredAt | timestamp сообщения Axon |
| commandId | сохранённый HTTP Idempotency-Key в WalletFact |

Metadata Axon хранится штатно и не является публичной версией или источником receipt.
История по afterVersion/limit сохраняет HTTP-пагинацию; учебный адаптер пока загружает историю
кошелька целиком перед формированием страницы. Это ограничение памяти и производительности.

Обычный GET читает только read model. Если строки нет, наличие истории отличает
PROJECTION_NOT_READY от WALLET_NOT_FOUND; баланс через replay в этом GET не подставляется.
atVersion восстанавливает префикс событий независимо от готовности read model.

JPA engine читает sourcing-порции в собственных транзакциях. Внешний REPEATABLE READ не даёт
общего снимка с этими чтениями. Поэтому comparison сначала читает проекцию версии P, затем
историю до наблюдаемой версии W и сверяет проекцию с неизменяемым префиксом **на версии P**.
При P<W результат LAGGING, P=W и равном состоянии — MATCHED. Разные состояния на одной версии
или P>W — ошибка целостности. Отсутствие проекции означает P=0. PendingEvents=W−P отражает
последовательные наблюдения, а не точный общий момент времени. UI это явно поясняет.

## Распределение обязанностей

| Обязанность | Собственная реализация 03 | Axon 04 | По-прежнему делает приложение |
|---|---|---|---|
| Доставка команд | прямой вызов сервиса | CommandGateway, command handling | HTTP и DTO |
| Восстановление команды | load + Wallet.rehydrate | event-sourced entity repository | применение доменных фактов |
| Сохранение/конкурентность | JdbcEventStore, CAS | JPA Event Store, aggregate sequence | expectedVersion и бизнес-правила |
| Асинхронная обработка | AsyncProjectionHandler | pooled streaming processor | WalletReadModelProjector |
| Постоянная позиция | projection_positions | JDBC Token Store | согласованность проекции |
| Идемпотентность | fingerprint + receipts | не заменяет контракт HTTP | ключ, fingerprint, исходный ответ |
| Диагностика | общий PostgreSQL snapshot | sourcing API | сравнение общего префикса |

Axon не отменяет бизнес-инварианты, проектирование проекций, обработку ошибок и идемпотентность API.
Event Sourcing определяет источник истины, CQRS разделяет модели записи/чтения, eventual consistency
описывает временное отставание. Отдельные БД и брокер здесь не нужны. Сходимость требует
возобновления processor и отсутствия неустранённой ошибки.

## Запуск и ограничения

Точные команды чистого запуска, порты, отдельный volume и ручной сценарий
«создание → пауза → пополнение → повтор → resume → сходимость» находятся в [README](../README.md).
Для проверки продолжения вручную остановите backend при накопившихся событиях и запустите снова
с тем же volume: token должен сохраниться, флаг паузы — нет. Этот сценарий автоматически не выполнялся.

Перенос данных прежних веток, административный rebuild/reset, snapshots, Saga и распределённая
доставка команд вне объёма. История Liquibase сформирована заново только для чистой БД этапа 04;
дальнейшие изменения требуют новых changesets. Существующие БД/volumes не удалялись.

Фактически выполнены `./gradlew dependencies --configuration runtimeClasspath`,
`./gradlew bootJar -x test` и `npm run build` в frontend — успешно.
Новые тесты не создавались, существующие тесты не запускались.

После исправления управления ошибкой shutdown обе сборки повторены успешно.
Для этой production-сборки UI использован доступный Node.js 24.19.0; системный 24.12.0
не поддерживается Angular CLI. Заявленный в package.json Node.js 24.21.0 и зависимости
не изменялись; эта сборка не подтверждает запуск именно на заявленной версии Node.js.
