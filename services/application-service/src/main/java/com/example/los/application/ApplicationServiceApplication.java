package com.example.los.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application service: system of record for loan applications.
 *
 * <p>Scheduling is enabled for the outbox publisher and the idempotency-key
 * purge. Both are background jobs; nothing on the request path is scheduled.
 */
@SpringBootApplication
@EnableScheduling
public class ApplicationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationServiceApplication.class, args);
    }
}
