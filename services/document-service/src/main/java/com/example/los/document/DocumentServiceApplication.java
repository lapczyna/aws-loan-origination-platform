package com.example.los.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Document service: metadata, presigned uploads and the quarantine lifecycle.
 *
 * <p>File bytes never pass through this service. Clients upload directly to S3
 * through short-lived presigned URLs, and the scanner reads from S3 itself.
 *
 * <p>Scheduling is enabled for the scanner, the abandoned-upload sweep and the
 * outbox publisher.
 */
@SpringBootApplication
@EnableScheduling
public class DocumentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
