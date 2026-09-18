package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка запуска учебного приложения: включает Spring MVC, JDBC и Liquibase.
 * Сканирует адаптеры и сценарии кошелька; домен остаётся чистой Java.
 */
@SpringBootApplication
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
