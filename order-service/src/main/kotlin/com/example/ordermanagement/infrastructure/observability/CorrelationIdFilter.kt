package com.example.ordermanagement.infrastructure.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Filter: CorrelationIdFilter
 *
 * ═══════════════════════════════════════════════════════════════════
 * CORRELATION IDs EXPLAINED
 * ═══════════════════════════════════════════════════════════════════
 * In a distributed system, a single user request may fan out to:
 *   - Multiple service calls
 *   - Temporal workflow execution
 *   - Multiple activity executions
 *   - Multiple log lines across different threads
 *
 * A correlation ID ties all of these together. Given any single log line,
 * you can filter by correlationId to see EVERYTHING that happened as
 * part of that request — across threads, activities, and services.
 *
 * FLOW:
 *   1. Inbound request arrives at REST controller
 *   2. This filter extracts X-Correlation-ID header (or generates one)
 *   3. Sets it in MDC (Mapped Diagnostic Context) for structured logging
 *   4. Returns the same ID in the response header
 *   5. All log lines in this request thread include [correlationId=xxx]
 *
 * The correlationId is also stored in event metadata, linking
 * event store entries back to specific HTTP requests.
 */
@Component
@Order(1)
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
        const val MDC_KEY = "correlationId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var correlationId = request.getHeader(CORRELATION_ID_HEADER)

        if (correlationId.isNullOrBlank()) {
            correlationId = UUID.randomUUID().toString()
        }

        // Put in MDC — available to all loggers in this thread
        MDC.put(MDC_KEY, correlationId)

        // Echo back in response for client correlation
        response.setHeader(CORRELATION_ID_HEADER, correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            // Always clean up MDC to prevent leaks in thread pools
            MDC.remove(MDC_KEY)
        }
    }
}
