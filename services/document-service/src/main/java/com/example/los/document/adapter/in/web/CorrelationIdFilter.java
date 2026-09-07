package com.example.los.document.adapter.in.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.los.document.observability.CorrelationId;

/**
 * Establishes the correlation identifier for the request and echoes it back.
 *
 * <p><strong>This filter deliberately does not touch the request or response
 * body.</strong> A generic request/response logging filter is the single most
 * common way personal data reaches a log aggregator: it cannot know which
 * endpoint it is wrapping, so it captures applicant names, tokens and document
 * metadata indiscriminately, and no amount of downstream regex masking reliably
 * removes them once they are in the pipeline. Logging on this platform is done
 * by explicit, hand-written statements that name the safe fields they emit.
 *
 * <p>This filter therefore logs nothing at all. Its whole job is to put a safe
 * identifier in the MDC and echo it back to the caller. Per-request access
 * logging belongs at the edge, where API Gateway records it with bodies,
 * Authorization headers and query strings explicitly excluded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = CorrelationId.sanitise(request.getHeader(CorrelationId.HEADER));
        CorrelationId.set(correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Cleared in a finally block because the thread returns to a pool. A
            // leaked MDC value would attach this request's correlation identifier
            // to an unrelated later request, which is worse than having none.
            CorrelationId.clear();
        }
    }
}
