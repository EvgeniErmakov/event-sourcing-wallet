package com.example.wallet.exception.api;

import java.beans.PropertyEditorSupport;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Строгая привязка UUID для path/header: только форма 8-4-4-4-12.
 * Одного Converter недостаточно: после ошибки преобразования binder может использовать
 * стандартный UUIDEditor, принимающий сокращённые группы. Явный редактор binder закрывает
 * этот обход. Новый экземпляр создаётся для каждой привязки и не разделяется между потоками.
 */
@ControllerAdvice(basePackages = "com.example.wallet.controller")
public class UuidBindingAdvice {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Отклоняет некорректную форму до вызова контроллера; API возвращает INVALID_REQUEST. */
    @InitBinder
    public void bindUuid(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String value) {
                if (value == null || !UUID_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException("Некорректный UUID");
                }
                setValue(UUID.fromString(value));
            }
        });
    }
}
