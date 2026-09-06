package com.example.los.events;

/** Aggregate type names used in {@link EventEnvelope#aggregateType()}. */
public final class AggregateTypes {

    public static final String LOAN_APPLICATION = "LoanApplication";
    public static final String DOCUMENT = "Document";
    public static final String WORKFLOW = "Workflow";

    private AggregateTypes() {}
}
