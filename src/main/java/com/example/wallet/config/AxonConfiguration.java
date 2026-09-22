package com.example.wallet.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.GeneralConverter;
import com.example.wallet.service.AxonWalletProjection;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.errorhandling.PropagatingErrorHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Общая физическая транзакция JPA/JDBC: один DataSource и JpaTransactionManager.
 * Spring-адаптер Axon привязывает оба executor к ProcessingContext и требует один поток.
 * Hibernate flush выполняется до commit; ошибка откатывает также JdbcTemplate receipt.
 */
@Configuration(proxyBeanMethods = false)
public class AxonConfiguration {
    @Bean
    public JpaTransactionManager transactionManager(EntityManagerFactory factory, DataSource source) {
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(source);
        return manager;
    }

    @Bean
    public EntityManagerProvider entityManagerProvider(EntityManagerFactory factory) {
        var shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        return () -> shared;
    }

    @Bean
    public org.axonframework.messaging.core.unitofwork.transaction.TransactionManager axonTransactionManager(
            JpaTransactionManager manager, EntityManagerProvider provider, DataSource source) {
        var definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new SpringTransactionManager(manager, provider, new SpringDataSourceConnectionProvider(source), definition);
    }

    /** Явный JDBC Token Store исключает автоматический выбор JPA/in-memory Token Store. DDL здесь нет. */
    @Bean
    public TokenStore tokenStore(DataSource source, GeneralConverter converter) {
        return new JdbcTokenStore(new JdbcTransactionalExecutorProvider(source), converter,
                JdbcTokenStoreConfiguration.DEFAULT);
    }

    /** Один segment сохраняет порядок; ошибка блокирует его целиком. Начало — первый факт, не хвост. */
    @Bean
    public PooledStreamingEventProcessorModule walletProcessor(ObjectProvider<AxonWalletProjection> projection,
            ObjectProvider<EventStore> events, ObjectProvider<TokenStore> tokens, ObjectProvider<UnitOfWorkFactory> units) {
        return EventProcessorModule.pooledStreaming("wallet-projection")
                .eventHandlingComponents(handlers -> handlers.declarative("wallet-facts", ignored -> projection.getObject()))
                .customized((configuration, config) -> config
                        .eventSource(events.getObject())
                        .tokenStore(tokens.getObject())
                        .unitOfWorkFactory(units.getObject())
                        .initialSegmentCount(1).maxClaimedSegments(1).batchSize(50)
                        .eventCriteria(ignored -> EventCriteria.havingAnyTag())
                        .initialToken(source -> source.firstToken(null))
                        .errorHandler(PropagatingErrorHandler.INSTANCE))
                .build();
    }
}
