package com.example.wallet.service;

import com.example.wallet.domain.event.WalletFact;
import com.example.wallet.exception.domain.CorruptHistoryException;
import com.example.wallet.serialization.EventSerializer;
import java.util.Set;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.stereotype.Component;

/** Строгий обработчик единственного потока событий приложения.
 * Подписка охватывает все типы: неизвестный тип откатывает порцию вместо молчаливого сдвига token.
 * Проекция и JdbcTokenStore используют общую транзакцию processor. Ошибки распространяются.
 */
@Component
public class AxonWalletProjection implements EventHandlingComponent {
    private final WalletReadModelProjector projector;
    private final EventSerializer serializer;
    private final EventConverter converter;

    public AxonWalletProjection(WalletReadModelProjector projector, EventSerializer serializer, EventConverter converter) {
        this.projector = projector;
        this.serializer = serializer;
        this.converter = converter;
    }

    @Override
    public Set<QualifiedName> supportedEvents() { return Set.of(new QualifiedName(WalletFact.EVENT_NAME)); }

    /** Даже неизвестный тип должен попасть в handle и завершиться ошибкой. */
    @Override
    public boolean supports(QualifiedName name) { return true; }

    /** Один учебный segment и одна последовательность исключают перестановку фактов кошелька. */
    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) { return "wallet-facts"; }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
        if (!WalletFact.EVENT_NAME.equals(event.type().qualifiedName().name()) || !"1".equals(event.type().version())) {
            throw new CorruptHistoryException("Неизвестный тип/формат Axon: " + event.type());
        }
        var fact = converter.convertPayload(event, WalletFact.class);
        try {
            projector.apply(fact.walletId(), fact.businessVersion(),
                    serializer.decode(fact.eventType(), fact.schemaVersion(), fact.payload()));
        } catch (RuntimeException failure) {
            throw new CorruptHistoryException("Ошибка проекции: walletId=" + fact.walletId()
                    + ", version=" + fact.businessVersion(), failure);
        }
        return MessageStream.empty();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) { descriptor.describeProperty("sequence", "wallet-facts"); }
}
