package ru.example.ukep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("ru.example.ukep")   // ← NEW: сканирует @ConfigurationProperties в пакете
public class UkepSignApplication {
    public static void main(String[] args) {
        SpringApplication.run(UkepSignApplication.class, args);
    }
}
