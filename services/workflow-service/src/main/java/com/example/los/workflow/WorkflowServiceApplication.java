package com.example.los.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Workflow service: durable assessment of submitted loan applications.
 *
 * <p>Scheduling is enabled for the workflow advancer and the outbox publisher.
 * Both are background jobs; the service exposes no request path that performs an
 * external check synchronously.
 */
@SpringBootApplication
@EnableScheduling
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
