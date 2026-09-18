package com.example.wallet.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Строгий UUID для path/header: UUID.fromString сам по себе принимает сокращённые группы.
 * API требует стандартную форму 8-4-4-4-12; регистр букв не меняет содержание команды.
 */
@Configuration(proxyBeanMethods = false)
public class UuidWebConfiguration implements WebMvcConfigurer {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, UUID.class, value -> {
            if (!UUID_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("Некорректный UUID");
            return UUID.fromString(value);
        });
    }
}
