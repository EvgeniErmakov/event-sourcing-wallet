package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка запуска учебного приложения: включает Spring MVC, Axon, JPA/JDBC и Liquibase.
 * Сканирует адаптеры и сценарии кошелька; Wallet использует модель event-sourced entity Axon.
 */
@SpringBootApplication
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
