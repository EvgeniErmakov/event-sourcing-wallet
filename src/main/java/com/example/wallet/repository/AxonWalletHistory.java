package com.example.wallet.repository;

import com.example.wallet.domain.event.WalletFact;
import com.example.wallet.exception.domain.CorruptHistoryException;
import com.example.wallet.serialization.EventSerializer;
import com.example.wallet.service.model.StoredEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TerminalEventMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.springframework.stereotype.Repository;

/** Читает единственную копию событий через конечный sourcing API Axon.
 * JPA engine читает порции в собственных транзакциях: внешний REPEATABLE READ сюда не переносится.
 * Каждая бизнес-версия проверяется; неизвестный payload не пропускается.
 */
@Repository
public class AxonWalletHistory implements WalletHistory {
    private final EventStore store;
    private final UnitOfWorkFactory units;
    private final EventConverter converter;
    private final EventSerializer serializer;
    public AxonWalletHistory(EventStore store, UnitOfWorkFactory units, EventConverter converter, EventSerializer serializer) {
        this.store = store;
        this.units = units;
        this.converter = converter;
        this.serializer = serializer;
    }
    @Override
    public List<StoredEvent> load(UUID walletId) {
        List<EventMessage> messages = units.create().executeWithResult(context -> {
            var stream = store.transaction(context).source(
                    SourcingCondition.conditionFor(EventCriteria.havingTags("Wallet", walletId.toString())));
            return stream.collect(() -> new ArrayList<EventMessage>(), List::add)
                    .whenComplete((result, error) -> stream.close());
        }).join();
        var result = new ArrayList<StoredEvent>();
        for (var message : messages) {
            if (message instanceof TerminalEventMessage) {
                continue;
            }
            if (!WalletFact.EVENT_NAME.equals(message.type().qualifiedName().name())
                    || !"1".equals(message.type().version())) {
                throw new CorruptHistoryException("Неизвестный тип или версия события Axon: " + message.type());
            }
            var fact = converter.convertPayload(message, WalletFact.class);
            if (fact == null || !walletId.equals(fact.walletId()) || fact.businessVersion() != result.size() + 1L) {
                throw new CorruptHistoryException("Нарушен порядок фактов Axon");
            }
            result.add(new StoredEvent(UUID.fromString(message.identifier()), walletId, fact.businessVersion(),
                    fact.eventType(), fact.schemaVersion(), serializer.decode(fact.eventType(), fact.schemaVersion(), fact.payload()),
                    message.timestamp(), fact.commandId()));
        }
        return List.copyOf(result);
    }
}
