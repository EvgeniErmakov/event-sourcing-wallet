package com.example.wallet.serialization;

import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.exception.domain.CorruptHistoryException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Явный реестр persisted event_type и единственного поддерживаемого schemaVersion=1.
 * Полное имя Java-класса не попадает в БД. Payload содержит только бизнес-поля,
 * streamVersion хранится отдельно. Неизвестные типы, форматы и поля — ошибка истории,
 * а не повод пропустить факт. Upcasters намеренно отсутствуют.
 */
@Component
public class EventSerializer {
    private static final Map<String, Class<? extends WalletEvent>> TYPES = Map.of(
            "WalletCreated", WalletEvent.WalletCreated.class,
            "MoneyDeposited", WalletEvent.MoneyDeposited.class,
            "MoneyWithdrawn", WalletEvent.MoneyWithdrawn.class,
            "WalletClosed", WalletEvent.WalletClosed.class);
    private final JsonMapper mapper;

    public EventSerializer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Кодирует новый факт с явно заданным стабильным именем и точным набором полей. */
    public Encoded encode(WalletEvent event) {
        String type = switch (event) {
            case WalletEvent.WalletCreated ignored -> "WalletCreated";
            case WalletEvent.MoneyDeposited ignored -> "MoneyDeposited";
            case WalletEvent.MoneyWithdrawn ignored -> "MoneyWithdrawn";
            case WalletEvent.WalletClosed ignored -> "WalletClosed";
        };
        Map<String, ?> fields = switch (event) {
            case WalletEvent.WalletCreated e -> Map.of("currency", e.currency());
            case WalletEvent.MoneyDeposited e -> Map.of("amountMinor", e.amountMinor());
            case WalletEvent.MoneyWithdrawn e -> Map.of("amountMinor", e.amountMinor());
            case WalletEvent.WalletClosed ignored -> Map.of();
        };
        return new Encoded(type, 1, mapper.writeValueAsString(fields));
    }

    /** Читает исторический факт без выполнения команды, проверки текущей версии или записи. */
    public WalletEvent decode(String type, int schemaVersion, String payload) {
        Class<? extends WalletEvent> eventClass = TYPES.get(type);
        if (eventClass == null || schemaVersion != 1) {
            throw new CorruptHistoryException("Неизвестный event_type/schema_version: " + type + "/" + schemaVersion);
        }
        try {
            JsonNode node = mapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Payload должен быть объектом");
            }
            switch (type) {
                case "WalletCreated" -> {
                    if (node.size() != 1 || !node.has("currency") || !node.get("currency").isString()
                            || !"RUB".equals(node.get("currency").stringValue())) {
                        throw new IllegalArgumentException("Некорректная валюта факта");
                    }
                }
                case "MoneyDeposited", "MoneyWithdrawn" -> {
                    JsonNode amount = node.get("amountMinor");
                    if (node.size() != 1 || amount == null || !amount.isIntegralNumber()
                            || !amount.canConvertToLong() || amount.longValue() <= 0) {
                        throw new IllegalArgumentException("Некорректная сумма факта");
                    }
                }
                case "WalletClosed" -> {
                    if (!node.isEmpty()) {
                        throw new IllegalArgumentException("Закрытие не содержит полей");
                    }
                }
                default -> throw new IllegalStateException("Тип отсутствует в реестре");
            }
            return mapper.treeToValue(node, eventClass);
        } catch (RuntimeException e) {
            throw new CorruptHistoryException("Повреждён payload события " + type, e);
        }
    }

    /** Сериализованное представление для одной INSERT; версия формата не равна версии потока. */
    public record Encoded(
            String eventType,
            int schemaVersion,
            String payload
    ) {
    }
}
