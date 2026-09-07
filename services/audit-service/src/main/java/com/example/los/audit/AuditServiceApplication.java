package com.example.los.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Audit service: an append-only, hash-chained record of what the platform did.
 *
 * <p>Consumes business events from every other context and writes one record per
 * event. It publishes nothing and exposes no write API: the only way into the
 * trail is through an event that already happened.
 */
@SpringBootApplication
@EnableScheduling
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
