# Изучение этапа 04

Сначала сравните [спецификацию этапов](PROJECT.md) и таблицу обязанностей в [axon.md](axon.md).
Event Sourcing означает хранение фактов, CQRS — разные модели чтения/записи,
eventual consistency — отдельный commit проекции. Одной PostgreSQL достаточно для всех трёх.

Порядок чтения реального кода:

1. Wallet: decide/apply, Axon handle/source; replay не вызывает бизнес-команду.
2. DispatchWalletCommand и WalletFact: UUID, ключ команды, businessVersion.
3. WalletCommandServiceImpl: gateway, ожидание commit, receipt после rollback.
4. AxonConfiguration: общий DataSource, JPA/JDBC transaction manager, JDBC tokens и один segment.
5. AxonWalletProjection и WalletReadModelProjector: применение принятого факта.
6. AxonWalletHistory и WalletQueryServiceImpl: чтение API Axon, atVersion и общий префикс comparison.
7. AxonProjectionHandlerServiceImpl: shutdown/start реального processor без reset.
8. Angular AppComponent: receipt отдельно от read model, версии по UUID, безопасный polling.

В этапах 01/02 replay и CAS были ответственностью приложения; в 03 оно также сохраняло
позиции polling. Теперь эту инфраструктуру предоставляет Axon. Приложение всё ещё отвечает
за деньги, idempotency fingerprint/receipt, проекции и реакцию на ошибки.

Read model производна и концептуально восстанавливается из событий, но административного
rebuild здесь нет. После паузы или перезапуска processor продолжает с token. Атомарность token
и проекции предотвращает повторный эффект зафиксированной порции, это не exactly-once delivery.

Команды сборки/запуска и ручной сценарий «пауза → пополнение → повтор → resume → сходимость»
приведены в [README](../README.md). Отдельно попробуйте рестарт с накопленными событиями.
Эти сценарии не запускались автоматически; компиляция не доказывает конкурентные гарантии.
