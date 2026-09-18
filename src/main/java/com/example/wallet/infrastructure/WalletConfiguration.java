package com.example.wallet.infrastructure;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import java.time.Clock;

/** Часы внедряются вне replay; строгий JSON не допускает округление денег и версий. */
@Configuration(proxyBeanMethods = false)
public class WalletConfiguration {
    @Bean
    public Clock walletClock() { return Clock.systemUTC(); }

    @Bean
    public JsonMapperBuilderCustomizer strictWalletJson() {
        return builder -> builder
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
